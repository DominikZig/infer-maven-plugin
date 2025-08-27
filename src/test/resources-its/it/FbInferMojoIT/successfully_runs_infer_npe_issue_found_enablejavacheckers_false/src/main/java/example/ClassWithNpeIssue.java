package example;

public class ClassWithNpeIssue {
  public static void main(String[] args) {
    System.out.println("NPE Issue Found");
  }

    private static void nullPointerDereference() {
        String str = null;
        int length = str.length();
    }
}
