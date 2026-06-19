import java.util.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public class Main {

    private static final Set<String> BUILTINS =
            Set.of("echo", "exit", "type");

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

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

                if (BUILTINS.contains(command)) {
                    System.out.println(command + " is a shell builtin");
                } else {
                    Path executable = findExecutable(command);

                    if (executable != null) {
                        System.out.println(command + " is " + executable);
                    } else {
                        System.out.println(command + ": not found");
                    }
                }
            }

            // external commands
            else {
                String[] parts = input.split(" ");
                String command = parts[0];

                Path executable = findExecutable(command);

                if (executable == null) {
                    System.out.println(command + ": command not found");
                    continue;
                }

                List<String> processArgs = new ArrayList<>();
                processArgs.add(command);

                // Add only the actual arguments, not the command again
                for (int i = 1; i < parts.length; i++) {
                    processArgs.add(parts[i]);
                }

                Process process = new ProcessBuilder(processArgs)
                        .inheritIO()
                        .start();

                process.waitFor();
            }
        }

        scanner.close();
    }

    private static Path findExecutable(String command) {
        String pathEnv = System.getenv("PATH");

        if (pathEnv == null) {
            return null;
        }

        String[] directories = pathEnv.split(File.pathSeparator);

        for (String dir : directories) {
            Path candidate = Path.of(dir, command);

            if (Files.exists(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }

        return null;
    }
}