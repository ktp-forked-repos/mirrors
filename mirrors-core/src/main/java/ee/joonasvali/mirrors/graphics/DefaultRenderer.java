package ee.joonasvali.mirrors.graphics;

import ee.joonasvali.mirrors.scene.Collidable;
import ee.joonasvali.mirrors.scene.LinePhysical;
import ee.joonasvali.mirrors.scene.Model;
import ee.joonasvali.mirrors.scene.Particle;
import ee.joonasvali.mirrors.scene.Physical;

import java.awt.*;


/**
 * @author Joonas Vali April 2016
 */
public class DefaultRenderer implements Renderer {
  @Override
  public void render(Model model, Graphics2D g) {
    synchronized (model.getLock()) {
      g.setColor(Color.BLACK);
      g.fillRect(0, 0, 1000, 1000);


      for (Collidable object : model.getObjects()) {
        if (object instanceof LinePhysical) {
          ((LinePhysical)object).render(g);
        }

        if (object instanceof Physical) {
          ((Physical)object).render(g);
        }
      }

      for (Particle particle : model.getParticles()) {
        particle.render(g);
      }
    }

    g.setColor(Color.darkGray);
    g.drawString(String.format("%.1f", model.getScore()), 10, 30);
    g.setColor(Color.darkGray);
  }
}
