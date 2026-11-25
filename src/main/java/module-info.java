module com.sapphircrypt.sapphirypt {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.media;
    requires javafx.web;
    requires java.desktop;
    requires jdk.management;
    requires org.bouncycastle.provider;

    exports com.sapphircrypt.sapphirypt;
    exports com.sapphircrypt.sapphirypt.crypto;
    exports com.sapphircrypt.sapphirypt.format;
    exports com.sapphircrypt.sapphirypt.storage;
    exports com.sapphircrypt.sapphirypt.ui;
}
