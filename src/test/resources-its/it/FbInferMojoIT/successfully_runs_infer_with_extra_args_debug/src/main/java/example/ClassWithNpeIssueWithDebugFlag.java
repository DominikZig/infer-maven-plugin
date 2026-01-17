package example;

public class ClassWithNpeIssueWithDebugFlag {
  public static void main(String[] args) {
    System.out.println("NPE Issue Found");
  }

    private static void nullPointerDereference() {
        String str = null;
        int length = str.length();
    }
}
