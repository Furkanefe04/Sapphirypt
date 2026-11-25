package com.sapphircrypt.sapphirypt.storage;

import com.sapphircrypt.sapphirypt.crypto.SensitiveBytes;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Enforces a single desktop application instance per user profile and forwards incoming
 * {@code .sapphirypt} file-open or window-focus requests over an authenticated loopback socket.
 */
public final class SingleInstanceCoordinator implements AutoCloseable {
    private static final String LOCK_FILE_NAME = "instance.lock";
    private static final String PORT_FILE_NAME = "instance.port";
    private static final int CLIENT_SOCKET_TIMEOUT_MS = 1500;
    private static final int SECONDARY_WAIT_MS = 600;
    private static final int MAX_IPC_LINE_CHARS = 8192;
    private static final HexFormat HEX = HexFormat.of();

    private final FileChannel lockChannel;
    private final FileLock fileLock;
    private final ServerSocket serverSocket;
    private final Path portFile;
    private final byte[] authTokenBytes;
    private final Thread serverThread;
    private volatile boolean closed;

    private SingleInstanceCoordinator(
            FileChannel lockChannel,
            FileLock fileLock,
            ServerSocket serverSocket,
            Path portFile,
            byte[] authTokenBytes,
            Consumer<Path> onOpenFile,
            Runnable onFocus) {
        this.lockChannel = lockChannel;
        this.fileLock = fileLock;
        this.serverSocket = serverSocket;
        this.portFile = portFile;
        this.authTokenBytes = authTokenBytes;
        this.serverThread = new Thread(() -> acceptLoop(onOpenFile, onFocus), "sapphirypt-ipc-server");
        this.serverThread.setDaemon(true);
        this.serverThread.start();
    }

    public static Path defaultStateDir() {
        return Path.of(System.getProperty("user.home"), ".sapphirypt");
    }

    /**
     * Attempts to become the primary instance using {@link #defaultStateDir()}.
     *
     * @return an active coordinator if this process holds the instance lock, or {@code null}
     * if the launch request was forwarded to an already-running primary instance.
     */
    public static SingleInstanceCoordinator tryAcquireOrForward(
            List<String> rawArgs,
            Consumer<Path> onOpenFile,
            Runnable onFocus) {
        return tryAcquireOrForward(defaultStateDir(), rawArgs, onOpenFile, onFocus);
    }

    static SingleInstanceCoordinator tryAcquireOrForward(
            Path stateDir,
            List<String> rawArgs,
            Consumer<Path> onOpenFile,
            Runnable onFocus) {
        Objects.requireNonNull(stateDir, "stateDir");
        try {
            Files.createDirectories(stateDir);
        } catch (IOException e) {
            return null;
        }

        Path lockPath = stateDir.resolve(LOCK_FILE_NAME);
        Path portPath = stateDir.resolve(PORT_FILE_NAME);

        FileChannel ch = null;
        FileLock lock = null;
        try {
            ch = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                lock = ch.tryLock();
            } catch (OverlappingFileLockException ignored) {
                lock = null;
            }

            if (lock != null) {
                return startPrimary(ch, lock, stateDir, portPath, onOpenFile, onFocus);
            }

            closeQuietly(ch);
            forwardToExistingInstance(portPath, rawArgs);
            return null;
        } catch (IOException ex) {
            releaseQuietly(lock);
            closeQuietly(ch);
            // If another instance holds the lock or I/O failed mid-handoff, exit safely rather than racing.
            return null;
        }
    }

    private static SingleInstanceCoordinator startPrimary(
            FileChannel ch,
            FileLock lock,
            Path stateDir,
            Path portPath,
            Consumer<Path> onOpenFile,
            Runnable onFocus) throws IOException {
        String token = HEX.formatHex(SensitiveBytes.randomBytes(16));
        byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);

        ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        int port = server.getLocalPort();

        writePortFileAtomically(stateDir, portPath, port, token);
        return new SingleInstanceCoordinator(ch, lock, server, portPath, tokenBytes, onOpenFile, onFocus);
    }

    private static void writePortFileAtomically(Path stateDir, Path portPath, int port, String token) throws IOException {
        String content = port + "\n" + token + "\n";
        Path tmp = Files.createTempFile(stateDir, "instance-", ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            restrictOwnerOnly(tmp);
            try {
                Files.move(tmp, portPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, portPath, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictOwnerOnly(portPath);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
        }
    }

    private static void forwardToExistingInstance(Path portPath, List<String> rawArgs) {
        long deadline = System.nanoTime() + SECONDARY_WAIT_MS * 1_000_000L;
        PortEndpoint endpoint = null;
        while (System.nanoTime() < deadline) {
            endpoint = readPortEndpoint(portPath);
            if (endpoint != null) {
                break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (endpoint == null) {
            return;
        }

        String command = buildForwardCommand(endpoint.token(), rawArgs);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port()), CLIENT_SOCKET_TIMEOUT_MS);
            socket.setSoTimeout(CLIENT_SOCKET_TIMEOUT_MS);
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write(command);
                writer.write('\n');
                writer.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private static String buildForwardCommand(String token, List<String> rawArgs) {
        if (rawArgs != null) {
            for (String raw : rawArgs) {
                if (raw != null && !raw.isBlank()) {
                    String cleaned = raw.replace("\r", "").replace("\n", "").replace("\t", " ").trim();
                    if (!cleaned.isEmpty()) {
                        Path normalized = Path.of(cleaned).toAbsolutePath().normalize();
                        return token + "\tOPEN\t" + normalized;
                    }
                }
            }
        }
        return token + "\tFOCUS";
    }

    private static PortEndpoint readPortEndpoint(Path portPath) {
        if (!Files.isRegularFile(portPath)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(portPath, StandardCharsets.UTF_8);
            if (lines.size() < 2) {
                return null;
            }
            int port = Integer.parseInt(lines.get(0).trim());
            String token = lines.get(1).trim();
            if (port <= 0 || port > 65535 || token.isEmpty()) {
                return null;
            }
            return new PortEndpoint(port, token);
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    private void acceptLoop(Consumer<Path> onOpenFile, Runnable onFocus) {
        while (!closed && !serverSocket.isClosed()) {
            try (Socket client = serverSocket.accept()) {
                client.setSoTimeout(CLIENT_SOCKET_TIMEOUT_MS);
                String line = readBoundedLine(client);
                if (line == null || line.isEmpty()) {
                    continue;
                }
                handleIncomingLine(line, onOpenFile, onFocus);
            } catch (IOException e) {
                if (closed || serverSocket.isClosed()) {
                    return;
                }
            }
        }
    }

    private static String readBoundedLine(Socket client) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(128);
            int ch;
            while ((ch = reader.read()) != -1) {
                if (ch == '\n' || ch == '\r') {
                    break;
                }
                if (sb.length() >= MAX_IPC_LINE_CHARS) {
                    return null;
                }
                sb.append((char) ch);
            }
            return sb.toString();
        }
    }

    private void handleIncomingLine(String line, Consumer<Path> onOpenFile, Runnable onFocus) {
        String[] parts = line.split("\t", 3);
        if (parts.length < 2) {
            return;
        }
        byte[] candidateBytes = parts[0].getBytes(StandardCharsets.UTF_8);
        if (!SensitiveBytes.constantTimeEquals(authTokenBytes, candidateBytes)) {
            return;
        }

        String verb = parts[1];
        if ("OPEN".equals(verb) && parts.length == 3 && !parts[2].isBlank()) {
            try {
                Path target = Path.of(parts[2]);
                if (onOpenFile != null) {
                    onOpenFile.accept(target);
                }
                if (onFocus != null) {
                    onFocus.run();
                }
            } catch (RuntimeException ignored) {
            }
        } else if ("FOCUS".equals(verb)) {
            try {
                if (onFocus != null) {
                    onFocus.run();
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void restrictOwnerOnly(Path path) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
        }
        try {
            AclFileAttributeView aclView = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (aclView != null) {
                UserPrincipal owner = Files.getOwner(path);
                AclEntry allowOwner = AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(owner)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .build();
                aclView.setAcl(List.of(allowOwner));
            }
        } catch (IllegalArgumentException | UnsupportedOperationException | IOException ignored) {
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        try {
            Files.deleteIfExists(portFile);
        } catch (IOException ignored) {
        }
        releaseQuietly(fileLock);
        closeQuietly(lockChannel);
        SensitiveBytes.wipe(authTokenBytes);
    }

    private static void releaseQuietly(FileLock lock) {
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }

    private record PortEndpoint(int port, String token) {
    }
}
