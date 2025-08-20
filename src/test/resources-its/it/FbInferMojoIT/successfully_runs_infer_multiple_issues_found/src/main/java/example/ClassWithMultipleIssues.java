package example;

import com.google.errorprone.annotations.ThreadSafe;

public class ClassWithMultipleIssues {
  public static void main(String[] args) {
    System.out.println("NPE and Resource Leak Issues Found");
  }

    private static void nullPointerDereference() {
        String str = null;
        int length = str.length();
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
