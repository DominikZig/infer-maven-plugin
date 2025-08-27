package example;

public class ClassWithCostIssue {
  public static void main(String[] args) {
    System.out.println("Cost Analysis Performed");
    int n = 100;
	quadratic(n);
  }

    public static int quadratic(int n) {
        int s = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                s += i + j;
            }
        }
        return s;
    }
}
