package ee.joonasvali.mirrors;

import ee.joonasvali.mirrors.scene.Constants;
import ee.joonasvali.mirrors.scene.genetic.GeneFactory;
import ee.joonasvali.mirrors.scene.genetic.Genepool;
import ee.joonasvali.mirrors.scene.genetic.GenepoolProvider;
import ee.joonasvali.mirrors.scene.genetic.impl.GeneratorGenepoolProvider;
import ee.joonasvali.mirrors.scene.genetic.util.SerializationUtil;
import ee.joonasvali.mirrors.util.KeepAliveUtil;
import ee.joonasvali.mirrors.watchmaker.GenepoolCanditateFactory;
import ee.joonasvali.mirrors.watchmaker.MutationOperator;
import ee.joonasvali.mirrors.watchmaker.SystemEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.uncommons.maths.random.MersenneTwisterRNG;
import org.uncommons.watchmaker.framework.EvaluatedCandidate;
import org.uncommons.watchmaker.framework.EvolutionEngine;
import org.uncommons.watchmaker.framework.EvolutionObserver;
import org.uncommons.watchmaker.framework.EvolutionaryOperator;
import org.uncommons.watchmaker.framework.GenerationalEvolutionEngine;
import org.uncommons.watchmaker.framework.PopulationData;
import org.uncommons.watchmaker.framework.TerminationCondition;
import org.uncommons.watchmaker.framework.operators.EvolutionPipeline;
import org.uncommons.watchmaker.framework.selection.RouletteWheelSelection;
import org.uncommons.watchmaker.framework.termination.TargetFitness;
import org.uncommons.watchmaker.framework.termination.UserAbort;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;

public class EvolutionController {
  public static final String EVOLUTION_PROPERTIES_FILE_NAME = "evolution.properties";
  public static final String POPULATIONS_FILE_NAME = "populations.json";
  private final static String nl = System.lineSeparator();
  private final static Logger log = LoggerFactory.getLogger(EvolutionController.class);
  private final Path evolutionDirectory;
  private final EvolutionProperties properties;

  public EvolutionController(Path evolutionDirectory) {
    Path evolutionPropertyFile = evolutionDirectory.resolve(EVOLUTION_PROPERTIES_FILE_NAME);
    EvolutionPropertyLoader loader = new EvolutionPropertyLoader();
    this.properties = loader.loadProperties(evolutionPropertyFile);
    this.evolutionDirectory = evolutionDirectory;
  }

  public Optional<Genepool> runEvolution(Collection<Genepool> seedPopulation) {
    log.info("Files saved to: " + evolutionDirectory);
    Random random = new MersenneTwisterRNG();
    final SerializationUtil serializer = new SerializationUtil(evolutionDirectory);
    GeneFactory geneFactory = new GeneFactory(properties, random);
    GenepoolProvider randomProvider = getProvider(geneFactory);
    GenepoolCanditateFactory candidateFactory = new GenepoolCanditateFactory(randomProvider);

    List<EvolutionaryOperator<Genepool>> operators = getEvolutionaryOperators(geneFactory, properties);
    EvolutionPipeline<Genepool> pipeline = new EvolutionPipeline<>(operators);
    UserAbort abortCondition = new UserAbort();

    Thread shutdownHook = new AbortOnShutdown(() -> {
      abortCondition.abort();
      // Wait until main thread successfully aborts and saves the state, by holding their shared lock.
      synchronized (abortCondition) {
        log.info("Aborting completed, continuing with shutdown.");
      }
    });

    Runtime.getRuntime().addShutdownHook(shutdownHook);

    EvolutionEngine<Genepool> engine
        = new GenerationalEvolutionEngine<>(
        candidateFactory,
        pipeline,
        new SystemEvaluator(),
        new RouletteWheelSelection(),
        random);

    engine.addEvolutionObserver(getEvolutionObserver(serializer));
    int targetFitness = properties.getTargetFitness();
    int concurrent = properties.getConcurrent();
    int elite = properties.getElites();

    if (properties.isKeepAlive()) {
      log.info("KeepAliveUtil activated");
      KeepAliveUtil.keepAlive();
    }

    log.info("Starting evolution process with target fitness " + nl + targetFitness + "." + nl +
        "Concurrent organisms: " + concurrent + nl + "Elite population: " + elite + nl);

    TerminationCondition targetFitnessCondition = new TargetFitness(targetFitness, true);

    Genepool winner;
    try {
      synchronized (abortCondition) {
        // This is a blocking call, evolution happens here.
        List<EvaluatedCandidate<Genepool>> population = engine.evolvePopulation(
            concurrent, elite, seedPopulation, targetFitnessCondition, abortCondition
        );

        List<TerminationCondition> conditions = engine.getSatisfiedTerminationConditions();
        if (conditions.contains(abortCondition) && !conditions.contains(targetFitnessCondition)) {
          serializer.serializePopulation(
              population.stream()
                  .map(EvaluatedCandidate::getCandidate)
                  .collect(Collectors.toList()),
              evolutionDirectory.resolve(POPULATIONS_FILE_NAME)
          );
          return Optional.empty();
        }

        Optional<EvaluatedCandidate<Genepool>> winnerCandidate = population.stream()
            .filter(genepool -> genepool.getFitness() >= targetFitness)
            .max(Comparator.comparingDouble(EvaluatedCandidate::getFitness));

        if (!winnerCandidate.isPresent()) {
          log.error("Something went wrong. Couldn't find winner that satisfies targetFitness. Returning best from population.");
          winnerCandidate = population.stream()
              .max(Comparator.comparingDouble(EvaluatedCandidate::getFitness));
        }

        if (winnerCandidate.isPresent()) {
          winner = winnerCandidate.get().getCandidate();
        } else {
          Runtime.getRuntime().removeShutdownHook(shutdownHook);
          throw new IllegalStateException("No population. Everyone's dead.");
        }

        serializer.serialize(winner, "winner");
        log.info("Evolution completed.");
      }
    } catch (Exception ex) {
      log.error("Fatal error during evolution", ex);
      Runtime.getRuntime().removeShutdownHook(shutdownHook);
      return Optional.empty();
    }

    Runtime.getRuntime().removeShutdownHook(shutdownHook);
    return Optional.of(winner);
  }

  private static GenepoolProvider getProvider(GeneFactory geneFactory) {
    return new GeneratorGenepoolProvider(geneFactory, Constants.DIMENSION_X, Constants.DIMENSION_Y);
  }

  private static EvolutionObserver<? super Genepool> getEvolutionObserver(SerializationUtil saver) {
    return new EvolutionObserver<Genepool>() {
      double last = 0;

      @Override
      public void populationUpdate(PopulationData<? extends Genepool> data) {
        log.info("Time: " + new Date());
        log.info("best of generation (" + data.getGenerationNumber() + "): " + data.getBestCandidateFitness());
        if (data.getBestCandidateFitness() > last) {
          try {
            saver.serialize(data.getBestCandidate(), data.getGenerationNumber() + "-" + (int) (data.getBestCandidateFitness()));
          } catch (IOException e) {
            log.error("Unable to save best candidate.", e);
          }
          last = data.getBestCandidateFitness();
        }
      }
    };
  }

  private static ArrayList<EvolutionaryOperator<Genepool>> getEvolutionaryOperators(GeneFactory geneFactory, EvolutionProperties properties) {
    ArrayList<EvolutionaryOperator<Genepool>> operators = new ArrayList<>();
    operators.add(new MutationOperator(geneFactory, properties.getGeneAdditionRate(), properties.getGeneDeletionRate()));
    return operators;
  }

  private static class AbortOnShutdown extends Thread {
    private static final Logger log = LoggerFactory.getLogger(AbortOnShutdown.class);
    private final Runnable abort;

    public AbortOnShutdown(Runnable abort) {
      this.abort = abort;
    }

    @Override
    public void run() {
      log.debug("Abort shutdown hook initiated!");
      abort.run();
      log.debug("Abort shutdown hook completed!");
    }
  }
}