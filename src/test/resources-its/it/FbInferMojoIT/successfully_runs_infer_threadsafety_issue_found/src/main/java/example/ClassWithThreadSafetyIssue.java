package example;

import com.google.errorprone.annotations.ThreadSafe;

public class ClassWithThreadSafetyIssue {
  public static void main(String[] args) {
    System.out.println("Thread Safety Issue Found");
  }

    @ThreadSafe
    public static class Dinner {

        private int mTemperature;

        public void makeDinner() {
            boilWater();
        }

        private void boilWater() {
            mTemperature = 100; // unprotected write.
        }

    }
}
