package com.sapphircrypt.sapphirypt.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipboardGuardAndI18nTest {

    @AfterEach
    void resetLanguage() {
        I18n.setLanguage("tr");
    }

    @Test
    void i18nSwitchesNormalizesAndNotifiesListeners() {
        I18n.setLanguage("tr");
        AtomicInteger notifications = new AtomicInteger();
        Runnable listener = notifications::incrementAndGet;
        I18n.addListener(listener);
        try {
            assertEquals("tr", I18n.language());
            assertFalse(I18n.isEnglish());
            assertEquals("Merhaba", I18n.t("Merhaba", "Hello"));

            I18n.setLanguage("EN");
            assertEquals("en", I18n.language());
            assertTrue(I18n.isEnglish());
            assertEquals("Hello", I18n.t("Merhaba", "Hello"));
            assertEquals(1, notifications.get());

            I18n.setLanguage("invalid-code");
            assertEquals("tr", I18n.language());
            assertFalse(I18n.isEnglish());
            assertEquals(2, notifications.get());
        } finally {
            I18n.removeListener(listener);
        }
    }

    @Test
    void clipboardGuardClearsMatchingPayloadAfterTimeout() throws Exception {
        AtomicReference<String> clipboard = new AtomicReference<>("secret-key-123");
        CountDownLatch clearedLatch = new CountDownLatch(1);

        try (ClipboardGuard guard = new ClipboardGuard(
                true,
                80L,
                clipboard::get,
                () -> {
                    clipboard.set(null);
                    clearedLatch.countDown();
                },
                Runnable::run)) {
            guard.recordSensitiveCopy("secret-key-123");
            assertTrue(clearedLatch.await(3, TimeUnit.SECONDS), "Matching clipboard content should be cleared");
            assertNull(clipboard.get());
        }
    }

    @Test
    void clipboardGuardSkipsClearWhenUserCopiedOtherText() throws Exception {
        AtomicReference<String> clipboard = new AtomicReference<>("unrelated-user-text");
        AtomicInteger clearCalls = new AtomicInteger();

        try (ClipboardGuard guard = new ClipboardGuard(
                true,
                70L,
                clipboard::get,
                clearCalls::incrementAndGet,
                Runnable::run)) {
            guard.recordSensitiveCopy("secret-key-123");
            Thread.sleep(220L);
            assertEquals(0, clearCalls.get());
            assertEquals("unrelated-user-text", clipboard.get());
        }
    }

    @Test
    void clipboardGuardClearPendingNowAndDisabledBehavior() {
        AtomicReference<String> clipboard = new AtomicReference<>("vault-secret");
        AtomicInteger clearCalls = new AtomicInteger();

        try (ClipboardGuard guard = new ClipboardGuard(
                true,
                5000L,
                clipboard::get,
                () -> {
                    clipboard.set(null);
                    clearCalls.incrementAndGet();
                },
                Runnable::run)) {
            guard.recordSensitiveCopy("vault-secret");
            guard.clearPendingNow();
            assertEquals(1, clearCalls.get());
            assertNull(clipboard.get());

            // When disabled, copying should not track or clear
            clipboard.set("vault-secret-2");
            guard.updateSettings(false, 30);
            guard.recordSensitiveCopy("vault-secret-2");
            guard.clearPendingNow();
            assertEquals(1, clearCalls.get());
            assertEquals("vault-secret-2", clipboard.get());
        }
    }
}
