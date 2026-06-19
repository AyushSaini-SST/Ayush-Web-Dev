import java.util.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public class Main {

    private static final Set<String> BUILTINS =
            Set.of("echo", "exit", "type", "pwd", "cd");

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        
        // Track the current working directory dynamically
        Path currentDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();

        while (true) {
            System.out.print("$ ");

            String input = scanner.nextLine().trim();

            if (input.isEmpty()) {
                continue;
            }

            // exit builtin
            if (input.equals("exit")) {
                break;
            }

            // pwd builtin
            else if (input.equals("pwd")) {
                System.out.println(currentDirectory);
            }

            // cd builtin
            else if (input.startsWith("cd ")) {
                String pathStr = input.substring(3).trim();
                Path targetPath;

                // Handle the ~ (home directory) symbol
                if (pathStr.equals("~")) {
                    String homeEnv = System.getenv("HOME");
                    targetPath = Path.of(homeEnv != null ? homeEnv : System.getProperty("user.home"));
                } else if (pathStr.startsWith("~/")) {
                    String homeEnv = System.getenv("HOME");
                    String homeDir = homeEnv != null ? homeEnv : System.getProperty("user.home");
                    targetPath = Path.of(homeDir, pathStr.substring(2));
                } else {
                    targetPath = Path.of(pathStr);
                }

                // If path is not absolute, resolve it relative to currentDirectory
                if (!targetPath.isAbsolute()) {
                    targetPath = currentDirectory.resolve(targetPath).normalize();
                } else {
                    targetPath = targetPath.normalize();
                }

                // Verify the directory exists and is a valid directory
                if (Files.exists(targetPath) && Files.isDirectory(targetPath)) {
                    currentDirectory = targetPath.toAbsolutePath();
                } else {
                    System.out.println("cd: " + pathStr + ": No such file or directory");
                }
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

                for (int i = 1; i < parts.length; i++) {
                    processArgs.add(parts[i]);
                }

                // Pass the current tracked directory to the process environment
                Process process = new ProcessBuilder(processArgs)
                        .directory(currentDirectory.toFile())
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