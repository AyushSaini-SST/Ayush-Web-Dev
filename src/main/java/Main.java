Set<String> builtins = Set.of("echo", "exit", "type");

...

else if (input.startsWith("type ")) {
    String command = input.substring(5);

    if (builtins.contains(command)) {
        System.out.println(command + " is a shell builtin");
    } else {
        String pathEnv = System.getenv("PATH");
        String[] directories = pathEnv.split(File.pathSeparator);

        boolean found = false;

        for (String dir : directories) {
            Path executablePath = Path.of(dir, command);

            if (Files.exists(executablePath)
                    && Files.isExecutable(executablePath)) {

                System.out.println(command + " is " +
                        executablePath.toAbsolutePath());

                found = true;
                break;
            }
        }

        if (!found) {
            System.out.println(command + ": not found");
        }
    }
}