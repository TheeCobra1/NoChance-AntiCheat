package NC.noChance.ml;

import NC.noChance.database.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

public class MLDataExport {
    private static final String HEADER = "id,timestamp,player_uuid,check_name,verdict,client_version,ping,tps,world,features_json,thresholds_json,staff_review_outcome,review_ts";

    private final Plugin plugin;
    private final DatabaseManager database;

    public MLDataExport(Plugin plugin, DatabaseManager database) {
        this.plugin = plugin;
        this.database = database;
    }

    public void run(CommandSender sender) {
        if (database == null || !database.isAvailable()) {
            sender.sendMessage("§cDatabase unavailable, cannot export.");
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        Path out = plugin.getDataFolder().toPath().resolve("ml_export_" + stamp + ".csv");
        sender.sendMessage("§7ML export started... writing to §f" + out.getFileName());

        AtomicReference<BufferedWriter> writerRef = new AtomicReference<>();
        try {
            Files.createDirectories(out.getParent());
            BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8);
            w.write(HEADER);
            w.newLine();
            writerRef.set(w);
        } catch (IOException e) {
            sender.sendMessage("§cFailed to open export file: " + e.getMessage());
            return;
        }

        database.streamMLForExport(row -> {
            try {
                BufferedWriter w = writerRef.get();
                if (w == null) return;
                w.write(toCsv(row));
                w.newLine();
            } catch (IOException ignored) {}
        }).whenComplete((count, ex) -> {
            BufferedWriter w = writerRef.getAndSet(null);
            if (w != null) {
                try { w.flush(); w.close(); } catch (IOException ignored) {}
            }
            int n = count == null ? 0 : count;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (ex != null) {
                    sender.sendMessage("§cExport failed: " + ex.getMessage());
                } else {
                    sender.sendMessage("§aML export done. wrote §f" + n + " §arows to §f" + out);
                }
            });
        });
    }

    private static String toCsv(MLDataCollector.Row r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.id).append(',');
        sb.append(r.ts).append(',');
        sb.append(csv(r.playerUuid)).append(',');
        sb.append(csv(r.checkName)).append(',');
        sb.append(csv(r.verdict)).append(',');
        sb.append(csv(r.clientVersion)).append(',');
        sb.append(r.ping).append(',');
        sb.append(r.tps).append(',');
        sb.append(csv(r.world)).append(',');
        sb.append(csv(r.features)).append(',');
        sb.append(csv(r.thresholds)).append(',');
        sb.append(csv(r.reviewOutcome)).append(',');
        sb.append(r.reviewTs);
        return sb.toString();
    }

    private static String csv(String s) {
        if (s == null) return "";
        boolean needQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!needQuote) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
