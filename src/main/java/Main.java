import java.util.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

public class Main {

    private static final Set<String> BUILTINS =
            Set.of("echo", "exit", "type", "pwd", "cd", "jobs");

    private static class Job {
        int jobNumber;
        long pid;
        String baseCommandString; 
        String status;
        List<Process> processes;

        Job(int jobNumber, long pid, String baseCommandString, List<Process> processes) {
            this.jobNumber = jobNumber;
            this.pid = pid;
            this.baseCommandString = baseCommandString;
            this.status = "Running";
            this.processes = processes;
        }

        boolean isAlive() {
            if (processes != null) {
                for (Process p : processes) {
                    if (p.isAlive()) return true;
                }
            }
            return false;
        }
    }

    private static final List<Job> activeJobs = new ArrayList<>();
    private static Path currentDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();

    private static void reapAndPrintJobs(boolean isJobsBuiltin) {
        for (Job job : activeJobs) {
            if ("Running".equals(job.status) && !job.isAlive()) {
                job.status = "Done";
            }
        }

        int totalJobs = activeJobs.size();
        List<Job> jobsToRemove = new ArrayList<>();

        for (int i = 0; i < totalJobs; i++) {
            Job job = activeJobs.get(i);
            char marker = (i == totalJobs - 1) ? '+' : ((i == totalJobs - 2) ? '-' : ' ');

            if ("Done".equals(job.status)) {
                jobsToRemove.add(job);
                String formattedStatus = String.format("%-24s", job.status);
                System.out.println("[" + job.jobNumber + "]" + marker + "  " + formattedStatus + job.baseCommandString);
            } else if (isJobsBuiltin && "Running".equals(job.status)) {
                String finalCommandOutput = job.baseCommandString + " &";
                String formattedStatus = String.format("%-24s", job.status);
                System.out.println("[" + job.jobNumber + "]" + marker + "  " + formattedStatus + finalCommandOutput);
            }
        }
        activeJobs.removeAll(jobsToRemove);
        System.out.flush();
    }

    private static void executeBuiltin(List<String> tokens, InputStream in, PrintStream out, PrintStream err) {
        String command = tokens.get(0);
        if (command.equals("pwd")) {
            out.println(currentDirectory);
        } else if (command.equals("echo")) {
            List<String> echoArgs = tokens.subList(1, tokens.size());
            out.println(String.join(" ", echoArgs));
        } else if (command.equals("type")) {
            if (tokens.size() < 2) return;
            String targetCommand = tokens.get(1);
            if (BUILTINS.contains(targetCommand)) {
                out.println(targetCommand + " is a shell builtin");
            } else {
                Path executable = findExecutable(targetCommand);
                if (executable != null) {
                    out.println(targetCommand + " is " + executable);
                } else {
                    out.println(targetCommand + ": not found");
                }
            }
        } else if (command.equals("jobs")) {
            for (Job job : activeJobs) {
                String formattedStatus = String.format("%-24s", job.status);
                out.println("[" + job.jobNumber + "]+  " + formattedStatus + job.baseCommandString + " &");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            reapAndPrintJobs(false);
            System.out.print("$ ");
            System.out.flush();

            if (!scanner.hasNextLine()) break;
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;

            List<String> parsedTokens = parseArguments(input);
            if (parsedTokens.isEmpty()) continue;

            boolean isBackground = false;
            if (parsedTokens.get(parsedTokens.size() - 1).equals("&")) {
                isBackground = true;
                parsedTokens.remove(parsedTokens.size() - 1);
            }
            if (parsedTokens.isEmpty()) continue;

            String baseCommandString = input;
            if (baseCommandString.endsWith("&")) {
                baseCommandString = baseCommandString.substring(0, baseCommandString.length() - 1).trim();
            }

            // --- MULTI-STAGE PIPELINE HANDLING ---
            if (parsedTokens.contains("|")) {
                List<List<String>> stages = new ArrayList<>();
                List<String> currentStage = new ArrayList<>();
                
                for (String token : parsedTokens) {
                    if (token.equals("|")) {
                        if (!currentStage.isEmpty()) {
                            stages.add(currentStage);
                            currentStage = new ArrayList<>();
                        }
                    } else {
                        currentStage.add(token);
                    }
                }
                if (!currentStage.isEmpty()) stages.add(currentStage);

                boolean mixedPipeline = false;
                for (List<String> stage : stages) {
                    if (BUILTINS.contains(stage.get(0))) {
                        mixedPipeline = true;
                        break;
                    }
                }

                // OPTIMIZED NATIVE OS PIPELINE FOR EXTERNAL COMMANDS ONLY
                if (!mixedPipeline) {
                    List<ProcessBuilder> builders = new ArrayList<>();
                    boolean allFound = true;
                    for (List<String> stage : stages) {
                        Path execPath = findExecutable(stage.get(0));
                        if (execPath == null) {
                            System.out.println(stage.get(0) + ": command not found");
                            allFound = false;
                            break;
                        }
                        stage.set(0, execPath.toAbsolutePath().toString());
                        builders.add(new ProcessBuilder(stage).directory(currentDirectory.toFile()));
                    }

                    if (!allFound) continue;

                    builders.get(0).redirectInput(ProcessBuilder.Redirect.INHERIT);
                    builders.get(builders.size() - 1).redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    for (ProcessBuilder pb : builders) {
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }

                    List<Process> pipelineProcesses = ProcessBuilder.startPipeline(builders);

                    if (isBackground) {
                        int assignedJobNumber = activeJobs.isEmpty() ? 1 : activeJobs.stream().mapToInt(j -> j.jobNumber).max().orElse(0) + 1;
                        long reportPid = pipelineProcesses.get(pipelineProcesses.size() - 1).pid();
                        System.out.println("[" + assignedJobNumber + "] " + reportPid);
                        activeJobs.add(new Job(assignedJobNumber, reportPid, baseCommandString, pipelineProcesses));
                    } else {
                        for (Process p : pipelineProcesses) p.waitFor();
                    }
                } 
                // MIXED / BUILT-IN PIPELINE REDIRECTION
                else {
                    List<Process> trackedProcesses = new ArrayList<>();
                    List<Thread> trackedThreads = new ArrayList<>(); // Track threads to avoid race condition
                    InputStream currentIn = System.in;

                    for (int i = 0; i < stages.size(); i++) {
                        List<String> stageTokens = stages.get(i);
                        String cmd = stageTokens.get(0);
                        boolean isLast = (i == stages.size() - 1);

                        PipedOutputStream po = null;
                        InputStream nextInStream = null;
                        if (!isLast) {
                            po = new PipedOutputStream();
                            nextInStream = new PipedInputStream(po);
                        }

                        final InputStream finalIn = currentIn;
                        final OutputStream finalOut = isLast ? System.out : po;

                        if (BUILTINS.contains(cmd)) {
                            Thread thread = new Thread(() -> {
                                try (PrintStream outPrint = new PrintStream(finalOut)) {
                                    executeBuiltin(stageTokens, finalIn, outPrint, System.err);
                                } finally {
                                    if (finalIn != System.in) {
                                        try { finalIn.close(); } catch (IOException ignored) {}
                                    }
                                }
                            });
                            trackedThreads.add(thread);
                            thread.start();
                        } else {
                            Path execPath = findExecutable(cmd);
                            if (execPath != null) {
                                stageTokens.set(0, execPath.toAbsolutePath().toString());
                                ProcessBuilder pb = new ProcessBuilder(stageTokens).directory(currentDirectory.toFile());
                                
                                if (finalIn == System.in) pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                                if (finalOut == System.out) pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                                pb.redirectError(ProcessBuilder.Redirect.INHERIT);

                                Process process = pb.start();
                                trackedProcesses.add(process);

                                if (finalIn != System.in) {
                                    Thread inputForwarder = new Thread(() -> {
                                        try (InputStream is = finalIn; OutputStream os = process.getOutputStream()) {
                                            is.transferTo(os);
                                            os.flush();
                                        } catch (IOException ignored) {}
                                        try { process.getOutputStream().close(); } catch (IOException ignored) {}
                                    });
                                    trackedThreads.add(inputForwarder);
                                    inputForwarder.start();
                                }
                                if (finalOut != System.out) {
                                    final OutputStream loopOut = finalOut;
                                    Thread outputForwarder = new Thread(() -> {
                                        try (InputStream is = process.getInputStream(); OutputStream os = loopOut) {
                                            is.transferTo(os);
                                            os.flush();
                                        } catch (IOException ignored) {}
                                        try { loopOut.close(); } catch (IOException ignored) {}
                                    });
                                    trackedThreads.add(outputForwarder);
                                    outputForwarder.start();
                                }
                            } else {
                                System.out.println(cmd + ": command not found");
                            }
                        }
                        currentIn = nextInStream;
                    }

                    if (!isBackground) {
                        for (Thread t : trackedThreads) t.join(); // Block shell until builtin thread output is completely flushed
                        for (Process p : trackedProcesses) p.waitFor();
                    }
                }
                continue;
            }

            // --- REDIRECTION PARSING ---
            String outputFile = null;
            int redirectIndex = -1;
            boolean isStderrRedirect = false;
            boolean isAppend = false;

            for (int i = 0; i < parsedTokens.size(); i++) {
                String token = parsedTokens.get(i);
                if (token.equals(">") || token.equals("1>") || token.equals("2>") || 
                    token.equals(">>") || token.equals("1>>") || token.equals("2>>")) {
                    if (i + 1 < parsedTokens.size()) {
                        outputFile = parsedTokens.get(i + 1);
                        redirectIndex = i;
                        isStderrRedirect = token.equals("2>") || token.equals("2>>");
                        isAppend = token.equals(">>") || token.equals("1>>") || token.equals("2>>");
                        break;
                    }
                }
            }

            List<String> commandTokens = (redirectIndex != -1) ? new ArrayList<>(parsedTokens.subList(0, redirectIndex)) : parsedTokens;
            if (commandTokens.isEmpty()) continue;

            String command = commandTokens.get(0);
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            PrintStream fileOutOrErr = null;

            if (outputFile != null && BUILTINS.contains(command)) {
                File file = new File(outputFile);
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                fileOutOrErr = new PrintStream(new FileOutputStream(file, isAppend));
                if (isStderrRedirect) System.setErr(fileOutOrErr); else System.setOut(fileOutOrErr);
            }

            try {
                if (command.equals("exit")) {
                    break;
                } else if (command.equals("jobs")) {
                    reapAndPrintJobs(true);
                } else if (command.equals("pwd") || command.equals("echo") || command.equals("type")) {
                    executeBuiltin(commandTokens, System.in, System.out, System.err);
                } else if (command.equals("cd")) {
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

                    if (!targetPath.isAbsolute()) targetPath = currentDirectory.resolve(targetPath).normalize();
                    else targetPath = targetPath.normalize();

                    if (Files.exists(targetPath) && Files.isDirectory(targetPath)) {
                        currentDirectory = targetPath.toAbsolutePath();
                    } else {
                        System.err.println("cd: " + pathStr + ": No such file or directory");
                    }
                } else {
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

                    ProcessBuilder pb = new ProcessBuilder(commandLine).directory(currentDirectory.toFile());

                    if (outputFile != null) {
                        File file = new File(outputFile);
                        File parent = file.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        if (isStderrRedirect) {
                            pb.redirectError(isAppend ? ProcessBuilder.Redirect.appendTo(file) : ProcessBuilder.Redirect.to(file));
                            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT); 
                        } else {
                            pb.redirectOutput(isAppend ? ProcessBuilder.Redirect.appendTo(file) : ProcessBuilder.Redirect.to(file));
                            pb.redirectError(ProcessBuilder.Redirect.INHERIT);  
                        }
                    } else {
                        pb.inheritIO();
                    }

                    Process process = pb.start();

                    if (isBackground) {
                        int assignedJobNumber = activeJobs.isEmpty() ? 1 : activeJobs.stream().mapToInt(j -> j.jobNumber).max().orElse(0) + 1;
                        System.out.println("[" + assignedJobNumber + "] " + process.pid());
                        activeJobs.add(new Job(assignedJobNumber, process.pid(), baseCommandString, List.of(process)));
                    } else {
                        process.waitFor();
                    }
                }
            } finally {
                if (fileOutOrErr != null) {
                    fileOutOrErr.close();
                    System.setOut(originalOut);
                    System.setErr(originalErr);
                }
                System.out.flush();
                System.err.flush();
            }
        }
    }

    private static List<String> parseArguments(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder currentArg = new StringBuilder();
        boolean inSingleQuotes = false, inDoubleQuotes = false, explicitArgument = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\\' && !inSingleQuotes && !inDoubleQuotes) {
                if (i + 1 < input.length()) { i++; currentArg.append(input.charAt(i)); explicitArgument = true; }
                continue;
            }
            if (c == '\\' && inDoubleQuotes) {
                if (i + 1 < input.length()) {
                    char nextChar = input.charAt(i + 1);
                    if (nextChar == '"' || nextChar == '\\' || nextChar == '$' || nextChar == '`') { i++; currentArg.append(nextChar); }
                    else currentArg.append(c);
                    explicitArgument = true;
                } else currentArg.append(c);
                continue;
            }
            if (c == '\'' && !inDoubleQuotes) { inSingleQuotes = !inSingleQuotes; explicitArgument = true; }
            else if (c == '"' && !inSingleQuotes) { inDoubleQuotes = !inDoubleQuotes; explicitArgument = true; }
            else if (c == ' ' && !inSingleQuotes && !inDoubleQuotes) {
                if (currentArg.length() > 0 || explicitArgument) { args.add(currentArg.toString()); currentArg.setLength(0); explicitArgument = false; }
            } else currentArg.append(c);
        }
        if (currentArg.length() > 0 || explicitArgument) args.add(currentArg.toString());
        return args;
    }

    private static Path findExecutable(String command) {
        Path directPath = Path.of(command);
        if (directPath.isAbsolute() || command.contains(File.separator)) {
            if (Files.exists(directPath) && Files.isExecutable(directPath)) return directPath;
            return null;
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String dir : pathEnv.split(File.pathSeparator)) {
            Path candidate = Path.of(dir, command);
            if (Files.exists(candidate) && Files.isExecutable(candidate)) return candidate;
        }
        return null;
    }
}