import java.util.Scanner;
import java.util.Set;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        Set<String> builtins = Set.of("echo", "exit", "type");

        while (true) {
            System.out.print("$ ");

            String input = scanner.nextLine();

            // exit builtin
            if (input.equals("exit")) {
                break;
            }

            // echo builtin
            else if (input.startsWith("echo ")) {
                System.out.println(input.substring(5));
            }

            // type builtin
            else if (input.startsWith("type ")) {
                String command = input.substring(5);

                // Check builtins first
                if (builtins.contains(command)) {
                    System.out.println(command + " is a shell builtin");
                } else {
                    String pathEnv = System.getenv("PATH");
                    String[] directories = pathEnv.split(File.pathSeparator);

                    boolean found = false;

                    for (String dir : directories) {
                        Path path = Path.of(dir, command);

                        if (Files.exists(path) && Files.isExecutable(path)) {
                            System.out.println(command + " is " + path);
                            found = true;
                            break;
                        }
                    }

                    if (!found) {
                        System.out.println(command + ": not found");
                    }
                }
            }

            // unknown command
            else {
                System.out.println(input + ": command not found");
            }
        }

        scanner.close();
    }
}