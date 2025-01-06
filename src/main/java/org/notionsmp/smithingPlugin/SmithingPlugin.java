package org.notionsmp.smithingPlugin;

import com.nexomc.nexo.api.NexoItems;
import com.nexomc.nexo.api.events.NexoItemsLoadedEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class SmithingPlugin extends JavaPlugin implements Listener {

    private final List<NamespacedKey> registeredRecipes = new ArrayList<>();
    private final Map<NamespacedKey, String> recipeConfigMap = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("nexosmithing")).setExecutor((sender, command, label, args) -> {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                loadRecipes();
                sender.sendMessage("Nexo smithing recipes reloaded");
                return true;
            }
            return false;
        });
    }

    @EventHandler
    public void onNexoItemsLoaded(NexoItemsLoadedEvent event) {
        getLogger().info("Nexo items loaded. Initializing recipes...");
        loadRecipes();
    }

    private void loadRecipes() {
        clearRegisteredRecipes();
        FileConfiguration config = getConfig();
        config.getKeys(false).forEach(recipeId -> {
            try {
                addSmithingTransformRecipe(recipeId, config);
            } catch (IllegalArgumentException e) {
                getLogger().warning("Failed to load recipe " + recipeId + ": " + e.getMessage());
            }
        });
    }

    private void clearRegisteredRecipes() {
        registeredRecipes.forEach(key -> {
            Bukkit.removeRecipe(key);
            getLogger().info("Removed recipe: " + key);
        });
        registeredRecipes.clear();
        recipeConfigMap.clear();
    }

    private void addSmithingTransformRecipe(String recipeId, FileConfiguration config) {
        ItemStack resultTemplate = parseItem(config, recipeId + ".result");
        RecipeChoice template = parseRecipeChoice(config, recipeId + ".template");
        RecipeChoice base = parseRecipeChoice(config, recipeId + ".base");
        RecipeChoice addition = parseRecipeChoice(config, recipeId + ".addition");

        if (resultTemplate == null || template == null || base == null || addition == null) {
            throw new IllegalArgumentException("Invalid recipe configuration for " + recipeId);
        }

        NamespacedKey key = new NamespacedKey(this, recipeId);

        if (Bukkit.getRecipe(key) == null) {
            SmithingTransformRecipe recipe = new SmithingTransformRecipe(key, resultTemplate, template, base, addition);
            Bukkit.addRecipe(recipe);
            registeredRecipes.add(key);
            recipeConfigMap.put(key, recipeId);
            getLogger().info("Registered smithing transform recipe: " + recipeId);
        } else {
            getLogger().info("Recipe " + recipeId + " already exists, skipping.");
        }
    }

    private ItemStack parseItem(FileConfiguration config, String path) {
        String nexoItemId = config.getString(path + ".nexo_item");
        if (nexoItemId != null) return Objects.requireNonNull(NexoItems.itemFromId(nexoItemId)).build();

        String materialName = config.getString(path + ".minecraft_item");
        assert materialName != null;
        Material material = Material.matchMaterial(materialName);
        return material != null ? new ItemStack(material) : null;
    }

    private RecipeChoice parseRecipeChoice(FileConfiguration config, String path) {
        String nexoItemId = config.getString(path + ".nexo_item");
        if (nexoItemId != null) return new RecipeChoice.ExactChoice(Objects.requireNonNull(NexoItems.itemFromId(nexoItemId)).build());

        String materialName = config.getString(path + ".minecraft_item");
        assert materialName != null;
        Material material = Material.matchMaterial(materialName);
        return material != null ? new RecipeChoice.MaterialChoice(material) : null;
    }

    @EventHandler
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        SmithingInventory inventory = event.getInventory();
        ItemStack templateItem = inventory.getItem(0);
        ItemStack baseItem = inventory.getItem(1);
        ItemStack additionItem = inventory.getItem(2);

        if (templateItem == null || baseItem == null || additionItem == null) {
            return;
        }

        for (NamespacedKey key : recipeConfigMap.keySet()) {
            SmithingTransformRecipe recipe = (SmithingTransformRecipe) Bukkit.getRecipe(key);
            if (recipe == null) continue;

            if (recipe.getTemplate().test(templateItem) &&
                    recipe.getBase().test(baseItem) &&
                    recipe.getAddition().test(additionItem)) {

                String recipeId = recipeConfigMap.get(key);
                FileConfiguration config = getConfig();
                boolean copyTrim = config.getBoolean(recipeId + ".copy_trim", true);
                boolean copyEnchants = config.getBoolean(recipeId + ".copy_enchantments", true);

                ItemStack result = recipe.getResult();
                ItemMeta baseMeta = baseItem.getItemMeta();
                ItemMeta resultMeta = result.getItemMeta();

                if (baseMeta != null && resultMeta != null) {
                    resultMeta.displayName(baseMeta.hasDisplayName() ? baseMeta.displayName() : resultMeta.displayName());
                    if (!resultMeta.hasLore()) {
                        resultMeta.lore(baseMeta.hasLore() ? baseMeta.lore() : resultMeta.lore());
                    }

                    if (copyEnchants) {
                        baseMeta.getEnchants().forEach((enchant, level) -> resultMeta.addEnchant(enchant, level, true));
                    }

                    if (copyTrim && baseMeta instanceof org.bukkit.inventory.meta.ArmorMeta baseArmorMeta) {
                        if (baseArmorMeta.hasTrim()) {
                            ((org.bukkit.inventory.meta.ArmorMeta) resultMeta).setTrim(baseArmorMeta.getTrim());
                        }
                    }

                    result.setItemMeta(resultMeta);
                }

                event.setResult(result);
                break;
            }
        }
    }
}
