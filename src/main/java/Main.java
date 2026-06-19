import java.util.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
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

            // --- REDIRECTION PARSING ---
            String outputFile = null;
            int redirectIndex = -1;
            boolean isStderrRedirect = false;

            for (int i = 0; i < parsedTokens.size(); i++) {
                String token = parsedTokens.get(i);
                if (token.equals(">") || token.equals("1>") || token.equals("2>")) {
                    if (i + 1 < parsedTokens.size()) {
                        outputFile = parsedTokens.get(i + 1);
                        redirectIndex = i;
                        isStderrRedirect = token.equals("2>");
                        break;
                    }
                }
            }

            List<String> commandTokens = parsedTokens;
            if (redirectIndex != -1) {
                // Separate the command and its arguments from the redirection instructions
                commandTokens = new ArrayList<>(parsedTokens.subList(0, redirectIndex));
            }

            if (commandTokens.isEmpty()) {
                continue;
            }

            String command = commandTokens.get(0);

            // Handle redirection stream capturing for Builtins
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            PrintStream fileOutOrErr = null;

            if (outputFile != null && BUILTINS.contains(command)) {
                File file = new File(outputFile);
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                fileOutOrErr = new PrintStream(new FileOutputStream(file));
                
                if (isStderrRedirect) {
                    System.setErr(fileOutOrErr);
                } else {
                    System.setOut(fileOutOrErr);
                }
            }

            try {
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
                    String pathStr = commandTokens.size() > 1 ? commandTokens.get(1) : "~";
                    Path targetPath;

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

                    if (!targetPath.isAbsolute()) {
                        targetPath = currentDirectory.resolve(targetPath).normalize();
                    } else {
                        targetPath = targetPath.normalize();
                    }

                    if (Files.exists(targetPath) && Files.isDirectory(targetPath)) {
                        currentDirectory = targetPath.toAbsolutePath();
                    } else {
                        System.err.println("cd: " + pathStr + ": No such file or directory");
                    }
                }

                // echo builtin
                else if (command.equals("echo")) {
                    List<String> echoArgs = commandTokens.subList(1, commandTokens.size());
                    System.out.println(String.join(" ", echoArgs));
                }

                // type builtin
                else if (command.equals("type")) {
                    if (commandTokens.size() < 2) {
                        continue;
                    }
                    String targetCommand = commandTokens.get(1);

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

                    List<String> commandLine = new ArrayList<>(commandTokens);
                    if (!command.contains(File.separator) && command.equals(executable.getFileName().toString())) {
                        commandLine.set(0, command);
                    } else {
                        commandLine.set(0, executable.toAbsolutePath().toString());
                    }

                    ProcessBuilder pb = new ProcessBuilder(commandLine)
                            .directory(currentDirectory.toFile());

                    // If redirection target exists for an external command
                    if (outputFile != null) {
                        File file = new File(outputFile);
                        File parent = file.getParentFile();
                        if (parent != null && !parent.exists()) {
                            parent.mkdirs();
                        }
                        
                        if (isStderrRedirect) {
                            pb.redirectError(file);
                            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT); // Keep stdout on terminal
                        } else {
                            pb.redirectOutput(file);
                            pb.redirectError(ProcessBuilder.Redirect.INHERIT);  // Keep stderr on terminal
                        }
                    } else {
                        pb.inheritIO();
                    }

                    Process process = pb.start();
                    process.waitFor();
                }
            } finally {
                // Revert system output stream safely if it was hijacked for builtins
                if (fileOutOrErr != null) {
                    fileOutOrErr.close();
                    System.setOut(originalOut);
                    System.setErr(originalErr);
                }
            }
        }

        scanner.close();
    }

    private static List<String> parseArguments(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder currentArg = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        boolean explicitArgument = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '\\' && !inSingleQuotes && !inDoubleQuotes) {
                if (i + 1 < input.length()) {
                    i++; 
                    currentArg.append(input.charAt(i));
                    explicitArgument = true;
                }
                continue;
            }

            if (c == '\\' && inDoubleQuotes) {
                if (i + 1 < input.length()) {
                    char nextChar = input.charAt(i + 1);
                    if (nextChar == '"' || nextChar == '\\' || nextChar == '$' || nextChar == '`') {
                        i++; 
                        currentArg.append(nextChar);
                    } else {
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
        Path directPath = Path.of(command);
        if (directPath.isAbsolute() || command.contains(File.separator)) {
            if (Files.exists(directPath) && Files.isExecutable(directPath)) {
                return directPath;
            }
            return null;
        }

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