import java.util.*;
import java.io.*;
import java.nio.file.*;

public class Main {

    private static final Set<String> BUILTINS =
            Set.of("echo", "exit", "type");

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");

            String input = scanner.nextLine();

            if (input.equals("exit")) {
                break;
            }

            else if (input.startsWith("echo ")) {
                System.out.println(input.substring(5));
            }

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

            else {
                String[] parts = input.split(" ");
                String command = parts[0];

                Path executable = findExecutable(command);

                if (executable == null) {
                    System.out.println(command + ": command not found");
                    continue;
                }

                List<String> processArgs = new ArrayList<>();
                processArgs.add(executable.toString());

                for (String part : parts) {
                    processArgs.add(part);
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