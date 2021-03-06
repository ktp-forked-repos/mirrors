package ee.joonasvali.mirrors.scene;

import java.awt.*;

public class Repellent extends RoundPhysical implements Collidable {
  public static final Color COLOR = new Color(71, 170, 104);

  private final double delta;
  private final double midX;
  private final double midY;

  public Repellent(double x, double y, double rotation, double radius, double delta) {
    super(x, y, rotation, radius);
    this.delta = Math.max(0, Math.min(1, delta));
    this.midX = x + radius;
    this.midY = y + radius;
  }

  @Override
  public boolean isCollision(Particle particle) {
    return CollisionUtil.areColliding(this, particle.getX(), particle.getY());
  }

  @Override
  public void actCollision(Particle particle, Model model) {
    if (delta > 0) {
      double distX = midX - particle.getX();
      double distY = midY - particle.getY();

      double forceX = radius - Math.abs(distX);
      double forceY = radius - Math.abs(distY);

      float angle = (float) Math.toDegrees(Math.atan2(particle.getY() - midY, particle.getX() - midX)) + 90;

      if (angle < 0) {
        angle += 360;
      }

      double force = Math.hypot(forceX, forceY) / 30d;
      double angleDiff = angle - particle.getAngle();

      while (angleDiff > 180) {
        angleDiff -= 360;
      }
      while (angleDiff < -180) {
        angleDiff += 360;
      }

      particle.setAngle(particle.getAngle() + angleDiff * force * delta);
    }
  }

  @Override
  public void render(Graphics2D g) {
    g.setColor(COLOR);
    g.drawOval((int) getX(), (int) getY(), (int) getWidth(), (int) getHeight());
    g.drawString(String.format("%.3f", delta), (float) getCenterX() - 10, (float) getCenterY() - 10);
    g.drawLine((int) midX, (int) midY, (int) midX, (int) midY);
  }
}
