package com.sapphircrypt.sapphirypt.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleInstanceCoordinatorTest {

    @Test
    void forwardsFileOpenAndFocusToPrimaryAndAllowsReacquireAfterClose(@TempDir Path tempDir) throws Exception {
        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch focusLatch = new CountDownLatch(2); // 1 from OPEN + 1 from FOCUS
        AtomicReference<Path> receivedPath = new AtomicReference<>();

        Path expectedVault = tempDir.resolve("sample vault.sapphirypt").toAbsolutePath().normalize();

        try (SingleInstanceCoordinator primary = SingleInstanceCoordinator.tryAcquireOrForward(
                tempDir,
                List.of(),
                path -> {
                    receivedPath.set(path);
                    openLatch.countDown();
                },
                focusLatch::countDown)) {
            assertNotNull(primary, "First instance must acquire the lock");

            SingleInstanceCoordinator secondaryOpen = SingleInstanceCoordinator.tryAcquireOrForward(
                    tempDir,
                    List.of(expectedVault.toString()),
                    path -> {
                    },
                    () -> {
                    });
            assertNull(secondaryOpen, "Secondary instance must forward OPEN request and return null");
            assertTrue(openLatch.await(3, TimeUnit.SECONDS), "Primary should receive OPEN path");
            assertEquals(expectedVault, receivedPath.get());

            SingleInstanceCoordinator secondaryFocus = SingleInstanceCoordinator.tryAcquireOrForward(
                    tempDir,
                    List.of(),
                    path -> {
                    },
                    () -> {
                    });
            assertNull(secondaryFocus, "Secondary instance with no args must forward FOCUS request and return null");
            assertTrue(focusLatch.await(3, TimeUnit.SECONDS), "Primary should receive FOCUS notification");
        }

        try (SingleInstanceCoordinator reacquired = SingleInstanceCoordinator.tryAcquireOrForward(
                tempDir,
                List.of(),
                path -> {
                },
                () -> {
                })) {
            assertNotNull(reacquired, "Lock must be reacquirable once primary closes");
        }
    }
}
