package example;

public class ClassWithBufferOverrunIssue {
  public static void main(String[] args) {
    System.out.println("Buffer Overrun Issue Found");
  }

    public static void writePastEnd() {
        int[] a = new int[5];
        for (int i = 0; i <= 5; i++) {
            a[i] = i;
        }
    }

    public static int readPastEnd() {
        int[] a = new int[3];
        a[0] = 1;
        a[1] = 2;
        a[2] = 3;
        return a[3];
    }
}
