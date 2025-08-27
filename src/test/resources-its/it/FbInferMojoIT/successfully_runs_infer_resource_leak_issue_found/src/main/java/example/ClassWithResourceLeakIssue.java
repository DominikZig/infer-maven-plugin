package example;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class ClassWithResourceLeakIssue {
  public static void main(String[] args) {
    System.out.println("Resource Leak Issue Found");
  }

    private static void resourceLeak() throws IOException {
        FileOutputStream stream;
        try {
            File file = new File("randomName.txt");
            stream = new FileOutputStream(file);
        } catch (IOException e) {
            return;
        }
        stream.write(0);
    }
}
