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

            // Parse the command line into arguments respecting quotes and selective backslash escapes
            List<String> parsedTokens = parseArguments(input);
            if (parsedTokens.isEmpty()) {
                continue;
            }

            String command = parsedTokens.get(0);

            // exit builtin
            if (command.equals("exit")) {
                break;
            }

            // pwd builtin
            else if (command.equals("pwd")) {
                System.out.println(currentDirectory);
            }

            // cd builtin
            else if (command.equals("cd")) {
                String pathStr = parsedTokens.size() > 1 ? parsedTokens.get(1) : "~";
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
            else if (command.equals("echo")) {
                List<String> echoArgs = parsedTokens.subList(1, parsedTokens.size());
                System.out.println(String.join(" ", echoArgs));
            }

            // type builtin
            else if (command.equals("type")) {
                if (parsedTokens.size() < 2) {
                    continue;
                }
                String targetCommand = parsedTokens.get(1);

                if (BUILTINS.contains(targetCommand)) {
                    System.out.println(targetCommand + " is a shell builtin");
                } else {
                    Path executable = findExecutable(targetCommand);

                    if (executable != null) {
                        System.out.println(targetCommand + " is " + executable);
                    } else {
                        System.out.println(targetCommand + ": not found");
                    }
                }
            }

            // external commands
            else {
                Path executable = findExecutable(command);

                if (executable == null) {
                    System.out.println(command + ": command not found");
                    continue;
                }

                // Pass the correctly parsed arguments directly to ProcessBuilder
                Process process = new ProcessBuilder(parsedTokens)
                        .directory(currentDirectory.toFile())
                        .inheritIO()
                        .start();

                process.waitFor();
            }
        }

        scanner.close();
    }

    /**
     * Parses a raw shell input line into discrete arguments.
     * Preserves inner quoted spaces and handles backslash escaping inside/outside quotes.
     */
    private static List<String> parseArguments(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder currentArg = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        boolean explicitArgument = false; // Flags empty quoted tokens

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            // 1. Handle backslash escaping OUTSIDE quotes
            if (c == '\\' && !inSingleQuotes && !inDoubleQuotes) {
                if (i + 1 < input.length()) {
                    i++; 
                    currentArg.append(input.charAt(i));
                    explicitArgument = true;
                }
                continue;
            }

            // 2. Handle backslash escaping INSIDE double quotes
            if (c == '\\' && inDoubleQuotes) {
                if (i + 1 < input.length()) {
                    char nextChar = input.charAt(i + 1);
                    // Standard rules: only escape double quotes, backslashes, $, `, and newline
                    if (nextChar == '"' || nextChar == '\\' || nextChar == '$' || nextChar == '`') {
                        i++; // Consume backslash, point directly to the escaped character
                        currentArg.append(nextChar);
                    } else {
                        // Keep backslash as a literal character
                        currentArg.append(c);
                    }
                    explicitArgument = true;
                } else {
                    currentArg.append(c);
                }
                continue;
            }

            if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
                explicitArgument = true;
            } else if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
                explicitArgument = true;
            } else if (c == ' ' && !inSingleQuotes && !inDoubleQuotes) {
                if (currentArg.length() > 0 || explicitArgument) {
                    args.add(currentArg.toString());
                    currentArg.setLength(0);
                    explicitArgument = false;
                }
            } else {
                currentArg.append(c);
            }
        }

        if (currentArg.length() > 0 || explicitArgument) {
            args.add(currentArg.toString());
        }

        return args;
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