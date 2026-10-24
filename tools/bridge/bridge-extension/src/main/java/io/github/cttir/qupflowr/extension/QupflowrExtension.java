/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.extension;

import io.github.cttir.qupflowr.core.bridge.BridgeServer;
import io.github.cttir.qupflowr.core.bridge.RuntimeReport;
import io.github.cttir.qupflowr.core.qupath.GeneralToolsVersionProbe;
import io.github.cttir.qupflowr.core.qupath.QuPath07Adapter;
import io.github.cttir.qupflowr.protocol.BuildInfo;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.tools.MenuTools;

import java.util.stream.Collectors;

/**
 * qupflowR QuPath extension: adds {@code Extensions > qupflowR} with commands to start and stop the
 * local bridge and a status line. With {@code -Dqupflowr.autostart=true} the bridge starts during
 * installation, on a background thread. Registered through {@code
 * META-INF/services/qupath.lib.gui.extensions.QuPathExtension}.
 */
public class QupflowrExtension implements QuPathExtension {

    private static final Logger logger = LoggerFactory.getLogger(QupflowrExtension.class);

    /** Name of the extension and of its submenu. */
    static final String EXTENSION_NAME = "qupflowR";

    /**
     * Menu path; {@code Menu.Extensions} is QuPath's resource key, so the lookup works in every
     * locale.
     */
    static final String MENU_PATH = "Menu.Extensions>" + EXTENSION_NAME;

    private final ReadOnlyBooleanWrapper running = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyStringWrapper statusText = new ReadOnlyStringWrapper("");
    private boolean installed;
    private BridgeController controller;
    private Messages messages =
            Messages.forLanguage(System.getProperty(Messages.PROPERTY_LANGUAGE));

    /**
     * Installs the menu and optionally starts the bridge. Called by QuPath on the JavaFX
     * application thread.
     *
     * @param qupath QuPath GUI instance
     */
    @Override
    public void installExtension(QuPathGUI qupath) {
        if (installed) {
            logger.debug("{} is already installed", EXTENSION_NAME);
            return;
        }
        installed = true;
        ExtensionSettings settings = ExtensionSettings.fromSystem();
        messages = Messages.forLanguage(settings.language());
        BuildInfo build = BuildInfo.current();
        logger.info(
                "Installing {} extension {} (fx_thread={}, runtime dir {}, language {})",
                EXTENSION_NAME,
                build.bridgeVersion(),
                Platform.isFxApplicationThread(),
                settings.runtimeDir(),
                settings.language());
        logger.info("{} runtime: {}", EXTENSION_NAME, RuntimeReport.describe());

        FxResponsivenessProbe probe = new FxResponsivenessProbe();
        controller =
                new BridgeController(
                        new QuPath07Adapter(new GeneralToolsVersionProbe()),
                        settings.runtimeDir(),
                        build.bridgeVersion(),
                        probe::isBlocked,
                        messages,
                        this::publishOnFxThread,
                        BridgeServer::start);
        statusText.set(controller.state().text());
        installMenu(qupath);
        if (settings.autostart()) {
            controller.startAsync("autostart");
        }
    }

    private void installMenu(QuPathGUI qupath) {
        Menu menu = qupath.getMenu(MENU_PATH, true);
        MenuItem start = new MenuItem(messages.get("menu.start"));
        start.setOnAction(e -> controller.startAsync("menu"));
        start.disableProperty().bind(running.getReadOnlyProperty());
        MenuItem stop = new MenuItem(messages.get("menu.stop"));
        stop.setOnAction(e -> controller.stopAsync("menu"));
        stop.disableProperty().bind(running.getReadOnlyProperty().not());
        MenuItem status = new MenuItem();
        status.textProperty().bind(statusText.getReadOnlyProperty());
        status.setDisable(true);
        menu.getItems().addAll(start, stop, new SeparatorMenuItem(), status);

        // Independent lookup on the live menu bar after the current pulse, logged for diagnostics.
        Platform.runLater(
                () -> {
                    Menu found =
                            MenuTools.getMenu(qupath.getMenuBar().getMenus(), MENU_PATH, false);
                    if (found == null) {
                        logger.error("Menu verification failed: {} not found", MENU_PATH);
                        return;
                    }
                    String items =
                            found.getItems().stream()
                                    .map(i -> i instanceof SeparatorMenuItem ? "---" : i.getText())
                                    .collect(Collectors.joining(" | "));
                    String parent =
                            found.getParentMenu() == null
                                    ? "(top level)"
                                    : found.getParentMenu().getText();
                    logger.info(
                            "Menu verification: found '{}' under '{}' with items [{}]",
                            found.getText(),
                            parent,
                            items);
                });
    }

    private void publishOnFxThread(BridgeController.State state) {
        Runnable update =
                () -> {
                    running.set(state.phase() == BridgeController.Phase.RUNNING);
                    statusText.set(state.text());
                    logger.info("qupflowR bridge state: {} ({})", state.phase(), state.text());
                };
        if (Platform.isFxApplicationThread()) {
            update.run();
        } else {
            Platform.runLater(update);
        }
    }

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public String getDescription() {
        return messages.get("extension.description");
    }

    @Override
    public Version getQuPathVersion() {
        return Version.parse(QuPath07Adapter.REQUIRED_VERSION);
    }

    @Override
    public Version getVersion() {
        return Version.parse(BuildInfo.current().bridgeVersion());
    }
}
