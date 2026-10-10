package blocky_game;

import java.io.File;
import javafx.application.Application;

public class Main {
    static {
        // JavaFX WebView can crash on some Windows setups when it tries to initialize the
        // HTML5 media pipeline (GStreamer) even though Blockly Maze doesn't require it.
        // Disable WebKit media player to avoid native JVM crashes in javafx.media.
        System.setProperty("com.sun.webkit.useMediaPlayer", "false");
        // Best-effort extra guard for older JavaFX media stacks.
        System.setProperty("com.sun.media.jfxmediaimpl.disableGStreamer", "true");

        // Fix for JavaFX Prism D3D texture pool race condition on Windows (JDK-8352209:
        // com.sun.prism.d3d.D3DTextureResource.getResource() returning null during texture updates):
        if (System.getProperty("prism.dirtyopts") == null) {
            System.setProperty("prism.dirtyopts", "false");
        }
        if (System.getProperty("prism.disableRegionCaching") == null) {
            System.setProperty("prism.disableRegionCaching", "true");
        }
        if (System.getProperty("prism.cacheshapes") == null) {
            System.setProperty("prism.cacheshapes", "false");
        }
        if (System.getProperty("prism.primtextures") == null) {
            System.setProperty("prism.primtextures", "false");
        }
        // Gated objectives (blocky_custom) and the edit-anywhere + wrap rules (defaultHenshinModule) are the defaults;
        // -Dblocky.objectives=CURRENT, -Dblocky.rules.wrap=false and -Dblocky.rules.editAnywhere=false turn them off.
        if (System.getProperty("blocky.henshin") == null) {
            String defaultModule = MomotFirstGoalBenchmarkRunner.defaultHenshinModule();
            String henshinPath = MomotRunService.firstExisting(
                    "blocky_model/transformations/" + defaultModule,
                    "../blocky_model/transformations/" + defaultModule,
                    defaultModule
            );
            File resolved = MomotRunService.resolveExistingFile(henshinPath);
            if (resolved.exists()) {
                System.setProperty("blocky.henshin", resolved.getAbsolutePath());
            }
        }
        // The solution panel also lists the non-goal candidates that came closest to the goal (Improvement-Plan.md,
        // section 3.6). Off in code, so the benchmark runners (their own main) are unchanged; -Dblocky.nonGoalArchive=0 turns it off here.
        if (System.getProperty("blocky.nonGoalArchive") == null) {
            System.setProperty("blocky.nonGoalArchive", "10");
        }
        if (System.getProperty("prism.maxvram") == null) {
            System.setProperty("prism.maxvram", "1G");
        }
        if (System.getProperty("prism.targetvram") == null) {
            System.setProperty("prism.targetvram", "512M");
        }
    }

    public static void main(String[] args) {
        boolean isServer = Boolean.getBoolean("server.mode") || Boolean.getBoolean("http.server");
        for (String arg : args) {
            if ("--server".equalsIgnoreCase(arg) || "-server".equalsIgnoreCase(arg)) {
                isServer = true;
                break;
            }
        }

        if (isServer) {
            try {
                System.out.println("[Main] Starting in Server REST API mode...");
                HttpSearchServer.main(args);
            } catch (Exception e) {
                System.err.println("[Main] Failed to start HttpSearchServer: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            Application.launch(BlockyUI.class, args);
        }
    }
}
