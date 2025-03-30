package com.mwester.savemanager;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SaveManager extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();

        int saveInterval = getConfig().getInt("save_interval", 3600); // Seconds
        int backupInterval = getConfig().getInt("backup_interval", 86400); // Seconds

        boolean enableBackups = getConfig().getBoolean("enable_backups", true);
        boolean enableBroadcast = getConfig().getBoolean("enable_broadcast", true);

        String preparingMessage = getConfig().getString("broadcast_messages.preparing", "&7[&aSaveManager&7] &fPreparing to save world progress...");
        String savingMessage = getConfig().getString("broadcast_messages.saving", "&7[&aSaveManager&7] &fSaving all worlds...");
        String backingUpMessage = getConfig().getString("broadcast_messages.backing_up", "&7[&aSaveManager&7] &fBacking up world files...");
        String backupCompleteMessage = getConfig().getString("broadcast_messages.backup_complete", "&7[&aSaveManager&7] &fBackup complete!");

        // Save Task
        new BukkitRunnable() {
            @Override
            public void run() {
                if (enableBroadcast) {
                    Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', preparingMessage));
                }
                Bukkit.getScheduler().runTaskLater(SaveManager.this, () -> {
                    if (enableBroadcast) {
                        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', savingMessage));
                    }
                    Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "save-all");
                    getLogger().info("Worlds saved automatically.");
                }, 100L); // 5 seconds later
            }
        }.runTaskTimer(this, saveInterval * 20L, saveInterval * 20L);

        // Backup Task (only if enabled)
        if (enableBackups) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (enableBroadcast) {
                        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', backingUpMessage));
                    }

                    Bukkit.getScheduler().runTaskAsynchronously(SaveManager.this, () -> {
                        backupWorld("world", getConfig().getString("backup_directories.overworld", "backups/overworld"));
                        backupWorld("world_nether", getConfig().getString("backup_directories.nether", "backups/nether"));
                        backupWorld("world_the_end", getConfig().getString("backup_directories.end", "backups/end"));

                        if (enableBroadcast) {
                            Bukkit.getScheduler().runTask(SaveManager.this, () -> {
                                Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', backupCompleteMessage));
                            });
                        }
                    });
                }
            }.runTaskTimer(this, backupInterval * 20L, backupInterval * 20L);
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("Auto Save disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("backup")) {
            if (!sender.hasPermission("savemanager.backup")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
                return true;
            }

            if (args.length != 1) {
                sender.sendMessage(ChatColor.RED + "Usage: /backup <world_name>");
                return true;
            }

            String worldName = args[0];
            String path = getConfig().getString("backup_directories." + worldName.toLowerCase(), "backups/" + worldName.toLowerCase());
            sender.sendMessage(ChatColor.YELLOW + "Starting backup for world: " + worldName);

            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                backupWorld(worldName, path);
                sender.sendMessage(ChatColor.GREEN + "Backup for world '" + worldName + "' completed.");
            });

            return true;
        }
        return false;
    }

    private void backupWorld(String worldName, String backupDirPath) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            getLogger().warning("World not found: " + worldName);
            return;
        }

        File worldFolder = world.getWorldFolder();
        File backupDir = new File(backupDirPath);
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }

        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        File zipFile = new File(backupDir, worldName + "_" + timestamp + ".zip");

        List<File> allFiles = new ArrayList<>();
        countFiles(worldFolder, allFiles);
        int totalFiles = allFiles.size();
        AtomicInteger filesZipped = new AtomicInteger(0);

        new Thread(() -> {
            while (filesZipped.get() < totalFiles) {
                try {
                    Thread.sleep(10000); // 10 seconds
                    int percent = (int) (((double) filesZipped.get() / totalFiles) * 100);
                    getLogger().info("Backing up '" + worldName + "': " + percent + "% complete (" + filesZipped.get() + "/" + totalFiles + ")");
                } catch (InterruptedException ignored) {}
            }
        }).start();

        try (FileOutputStream fos = new FileOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            zipDirectory(worldFolder, worldFolder.getName(), zos, filesZipped);
            getLogger().info("Backed up world " + worldName + " to " + zipFile.getPath());
        } catch (IOException e) {
            getLogger().severe("Failed to back up world " + worldName + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void countFiles(File folder, List<File> allFiles) {
        File[] files = folder.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                countFiles(file, allFiles);
            } else {
                allFiles.add(file);
            }
        }
    }

    private void zipDirectory(File folder, String parentFolder, ZipOutputStream zos, AtomicInteger progressCounter) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                zipDirectory(file, parentFolder + "/" + file.getName(), zos, progressCounter);
                continue;
            }

            try (FileInputStream fis = new FileInputStream(file)) {
                String zipEntryName = parentFolder + "/" + file.getName();
                zos.putNextEntry(new ZipEntry(zipEntryName));

                byte[] buffer = new byte[1024];
                int length;
                while ((length = fis.read(buffer)) >= 0) {
                    zos.write(buffer, 0, length);
                }
                zos.closeEntry();
                progressCounter.incrementAndGet();
            }
        }
    }
}
