package org.leavesmc.leaves.protocol.rei;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SmithingTemplateItem;
import net.minecraft.world.item.crafting.FireworkRocketRecipe;
import net.minecraft.world.item.crafting.MapCloningRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import net.minecraft.world.item.crafting.TippedArrowRecipe;
import net.minecraft.world.item.crafting.TransmuteRecipe;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.LeavesConfig;
import org.leavesmc.leaves.protocol.core.LeavesProtocol;
import org.leavesmc.leaves.protocol.core.ProtocolHandler;
import org.leavesmc.leaves.protocol.core.ProtocolUtils;
import org.leavesmc.leaves.protocol.rei.display.BlastingDisplay;
import org.leavesmc.leaves.protocol.rei.display.CampfireDisplay;
import org.leavesmc.leaves.protocol.rei.display.Display;
import org.leavesmc.leaves.protocol.rei.display.ShapedDisplay;
import org.leavesmc.leaves.protocol.rei.display.ShapelessDisplay;
import org.leavesmc.leaves.protocol.rei.display.SmeltingDisplay;
import org.leavesmc.leaves.protocol.rei.display.SmokingDisplay;
import org.leavesmc.leaves.protocol.rei.display.StoneCuttingDisplay;
import org.leavesmc.leaves.protocol.rei.payload.BufCustomPacketPayload;
import org.leavesmc.leaves.protocol.rei.payload.DisplaySyncPayload;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

@LeavesProtocol(namespace = REIServerProtocol.PROTOCOL_ID)
public class REIServerProtocol {

    public static final String PROTOCOL_ID = "roughlyenoughitems";
    public static final String CHEAT_PERMISSION = "leaves.protocol.rei.cheat";
    public static final ResourceLocation DELETE_ITEMS_PACKET = ResourceLocation.fromNamespaceAndPath("roughlyenoughitems", "delete_item");
    public static final ResourceLocation CREATE_ITEMS_PACKET = ResourceLocation.fromNamespaceAndPath("roughlyenoughitems", "create_item");
    public static final ResourceLocation CREATE_ITEMS_GRAB_PACKET = ResourceLocation.fromNamespaceAndPath("roughlyenoughitems", "create_item_grab");
    public static final ResourceLocation CREATE_ITEMS_HOTBAR_PACKET = ResourceLocation.fromNamespaceAndPath("roughlyenoughitems", "create_item_hotbar");
    public static final ResourceLocation CREATE_ITEMS_MESSAGE_PACKET = ResourceLocation.fromNamespaceAndPath("roughlyenoughitems", "ci_msg");
    public static final ResourceLocation SYNC_DISPLAYS_PACKET = ResourceLocation.fromNamespaceAndPath("roughlyenoughitems", "sync_displays");

    public static final Map<ResourceLocation, PacketTransformer> TRANSFORMERS = Util.make(() -> {
        ImmutableMap.Builder<ResourceLocation, PacketTransformer> builder = ImmutableMap.builder();
        builder.put(SYNC_DISPLAYS_PACKET, new PacketTransformer());
        builder.put(DELETE_ITEMS_PACKET, new PacketTransformer());
        builder.put(CREATE_ITEMS_PACKET, new PacketTransformer());
        builder.put(CREATE_ITEMS_GRAB_PACKET, new PacketTransformer());
        builder.put(CREATE_ITEMS_HOTBAR_PACKET, new PacketTransformer());
        return builder.build();
    });
    private static final Set<ServerPlayer> enabledPlayers = new HashSet<>();
    private static int minecraftRecipeVer = 0;
    private static int nextReiRecipeVer = -1;
    private static ImmutableList<CustomPacketPayload> cachedPayloads;
    private static final Executor executor = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1),
        new ThreadPoolExecutor.DiscardOldestPolicy()
    );

    public static void onRecipeReload() {
        minecraftRecipeVer = MinecraftServer.getServer().getTickCount();
    }

    @Contract("_ -> new")
    public static ResourceLocation id(String path) {
        return ResourceLocation.tryBuild(PROTOCOL_ID, path);
    }

    public static void onConfigModify(boolean enabled) {
        PluginManager pluginManager = Bukkit.getServer().getPluginManager();
        if (enabled) {
            if (pluginManager.getPermission(CHEAT_PERMISSION) == null) {
                pluginManager.addPermission(new Permission(CHEAT_PERMISSION, PermissionDefault.OP));
            }
        } else {
            pluginManager.removePermission(CHEAT_PERMISSION);
            enabledPlayers.clear();
        }
    }

    @ProtocolHandler.PlayerLeave
    public static void onPlayerLoggedOut(@NotNull ServerPlayer player) {
        if (LeavesConfig.protocol.reiServerProtocol) {
            enabledPlayers.remove(player);
        }
    }

    @ProtocolHandler.Ticker
    public static void tick() {
        if (!LeavesConfig.protocol.reiServerProtocol) {
            return;
        }
        if (MinecraftServer.getServer().getTickCount() % 200 == 1 && minecraftRecipeVer != nextReiRecipeVer) {
            nextReiRecipeVer = minecraftRecipeVer;
            executor.execute(() -> reloadRecipe(nextReiRecipeVer));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void reloadRecipe(int reiRecipeVer) {
        ImmutableList.Builder<Display> builder = ImmutableList.builder();
        MinecraftServer server = MinecraftServer.getServer();
        RecipeMap recipeMap = server.getRecipeManager().recipes;
        recipeMap.byType(RecipeType.CRAFTING).forEach(holder -> {
            switch (holder.value()) {
                case ShapedRecipe ignored -> builder.add(new ShapedDisplay((RecipeHolder) holder));
                case ShapelessRecipe ignored -> builder.add(new ShapelessDisplay((RecipeHolder) holder));
                case TransmuteRecipe ignored -> builder.addAll(Display.ofTransmuteRecipe((RecipeHolder) holder));
                case TippedArrowRecipe ignored -> builder.addAll(Display.ofTippedArrowRecipe((RecipeHolder) holder));
                case FireworkRocketRecipe ignored -> builder.addAll(Display.ofFireworkRocketRecipe((RecipeHolder) holder));
                case MapCloningRecipe ignored -> builder.addAll(Display.ofMapCloningRecipe((RecipeHolder) holder));
                // ignore ArmorDyeRecipe, BannerDuplicateRecipe, BookCloningRecipe, ShieldDecorationRecipe
                default -> {
                }
            }
        });
        recipeMap.byType(RecipeType.STONECUTTING).forEach(holder -> builder.add(new StoneCuttingDisplay(holder)));
        recipeMap.byType(RecipeType.SMELTING).forEach(holder -> builder.add(new SmeltingDisplay(holder)));
        recipeMap.byType(RecipeType.BLASTING).forEach(holder -> builder.add(new BlastingDisplay(holder)));
        recipeMap.byType(RecipeType.SMOKING).forEach(holder -> builder.add(new SmokingDisplay(holder)));
        recipeMap.byType(RecipeType.CAMPFIRE_COOKING).forEach(holder -> builder.add(new CampfireDisplay(holder)));
        recipeMap.byType(RecipeType.SMITHING).forEach(holder -> {
            switch (holder.value()) {
                case SmithingTrimRecipe ignored -> builder.addAll(Display.ofSmithingTrimRecipe((RecipeHolder) holder));
                case SmithingTemplateItem ignored -> builder.add(Display.ofTransforming((RecipeHolder) holder));
                default -> {
                }
            }
        });

        DisplaySyncPayload displaySyncPayload = new DisplaySyncPayload(
            DisplaySyncPayload.SyncType.SET,
            builder.build(),
            reiRecipeVer
        );

        RegistryFriendlyByteBuf wholeBuf = ProtocolUtils.decorate(Unpooled.buffer());
        DisplaySyncPayload.STREAM_CODEC.encode(wholeBuf, displaySyncPayload);
        ResourceLocation location = SYNC_DISPLAYS_PACKET;
        ImmutableList.Builder<CustomPacketPayload> listBuilder = ImmutableList.builder();

        PacketTransformer transformer = TRANSFORMERS.get(location);
        if (transformer != null) {
            transformer.outbound(location, wholeBuf, (id, splitBuf) -> {
                BufCustomPacketPayload splitBufPayload = new BufCustomPacketPayload(new CustomPacketPayload.Type<>(id), ByteBufUtil.getBytes(splitBuf));
                listBuilder.add(splitBufPayload);
            });
        } else {
            listBuilder.add(new BufCustomPacketPayload(new CustomPacketPayload.Type<>(location), ByteBufUtil.getBytes(wholeBuf)));
        }

        cachedPayloads = listBuilder.build();
        MinecraftServer.getServer().execute(() -> {
            for (ServerPlayer player : enabledPlayers) {
                for (CustomPacketPayload payload : cachedPayloads) {
                    ProtocolUtils.sendPayloadPacket(player, payload);
                }
            }
        });
    }

    @ProtocolHandler.MinecraftRegister(ignoreId = true)
    public static void onPlayerSubscribed(@NotNull ServerPlayer player, String channel) {
        if (!LeavesConfig.protocol.reiServerProtocol) {
            return;
        }
        enabledPlayers.add(player);
        if (channel.equals("sync_displays")) {
            if (cachedPayloads != null) {
                cachedPayloads.forEach(payload -> ProtocolUtils.sendPayloadPacket(player, payload));
            }
        } else if (channel.equals("ci_msg")) {
            // cheat rei-client into using "delete_item" packet
            if (player.getServer().getProfilePermissions(player.getGameProfile()) < 1) {
                player.getBukkitEntity().sendOpLevel((byte) 1);
            }
        }
    }

    @ProtocolHandler.PayloadReceiver(payload = BufCustomPacketPayload.class, payloadId = "delete_item")
    public static void handleDeleteItem(ServerPlayer player, BufCustomPacketPayload payload) {
        if (!LeavesConfig.protocol.reiServerProtocol || !hasCheatPermission(player)) {
            return;
        }
        RegistryFriendlyByteBuf c2sBuf = ProtocolUtils.decorate(Unpooled.buffer());
        c2sBuf.writeBytes(payload.payload());

        useTransformer(player, payload.id(), c2sBuf, true, (id, wholeBuf) -> {
            AbstractContainerMenu menu = player.containerMenu;
            if (!menu.getCarried().isEmpty()) {
                menu.setCarried(ItemStack.EMPTY);
                menu.broadcastChanges();
            }
        });
    }

    @ProtocolHandler.PayloadReceiver(payload = BufCustomPacketPayload.class, payloadId = "create_item")
    public static void handleCreateItem(ServerPlayer player, BufCustomPacketPayload payload) {
        if (!LeavesConfig.protocol.reiServerProtocol || !hasCheatPermission(player)) {
            return;
        }
        RegistryFriendlyByteBuf c2sBuf = ProtocolUtils.decorate(Unpooled.buffer());
        c2sBuf.writeBytes(payload.payload());
        BiConsumer<ResourceLocation, RegistryFriendlyByteBuf> consumer = (ignored, c2sWholeBuf) -> {
            FriendlyByteBuf tmpBuf = new FriendlyByteBuf(Unpooled.buffer()).writeBytes(c2sWholeBuf.readByteArray());
            ItemStack itemStack = tmpBuf.readJsonWithCodec(ItemStack.OPTIONAL_CODEC);
            if (player.getInventory().add(itemStack.copy())) {
                RegistryFriendlyByteBuf s2cWholeBuf = ProtocolUtils.decorate(Unpooled.buffer());
                s2cWholeBuf.writeJsonWithCodec(ItemStack.OPTIONAL_CODEC, itemStack.copy());
                s2cWholeBuf.writeUtf(player.getScoreboardName(), 32767);
                // Due to the bug in REI, no packets are actually sent here.
                /*
                userTransformer(player, CREATE_ITEMS_MESSAGE_PACKET, s2cWholeBuf, false (id, s2cSplitBuf) -> {
                    ProtocolUtils.sendPayloadPacket(player, new BufCustomPacketPayload(new CustomPacketPayload.Type<>(id), ByteBufUtil.getBytes(s2cSplitBuf)));
                });
                */
            } else {
                player.displayClientMessage(Component.translatable("text.rei.failed_cheat_items"), false);
            }
        };
        useTransformer(player, payload.id(), c2sBuf, true, consumer);
    }

    @ProtocolHandler.PayloadReceiver(payload = BufCustomPacketPayload.class, payloadId = "create_item_grab")
    public static void handleCreateItemGrab(ServerPlayer player, BufCustomPacketPayload payload) {
        if (!LeavesConfig.protocol.reiServerProtocol || !hasCheatPermission(player)) {
            return;
        }
        RegistryFriendlyByteBuf c2sBuf = ProtocolUtils.decorate(Unpooled.buffer());
        c2sBuf.writeBytes(payload.payload());

        BiConsumer<ResourceLocation, RegistryFriendlyByteBuf> consumer = (ignored, c2sWholeBuf) -> {
            FriendlyByteBuf tmpBuf = new FriendlyByteBuf(Unpooled.buffer()).writeBytes(c2sWholeBuf.readByteArray());
            ItemStack itemStack = tmpBuf.readJsonWithCodec(ItemStack.OPTIONAL_CODEC);
            ItemStack stack = itemStack.copy();
            AbstractContainerMenu menu = player.containerMenu;
            if (!menu.getCarried().isEmpty() && ItemStack.isSameItemSameComponents(menu.getCarried(), stack)) {
                stack.setCount(Mth.clamp(stack.getCount() + menu.getCarried().getCount(), 1, stack.getMaxStackSize()));
            } else if (!menu.getCarried().isEmpty()) {
                return;
            }
            menu.setCarried(stack.copy());
            menu.broadcastChanges();
            RegistryFriendlyByteBuf s2cWholeBuf = ProtocolUtils.decorate(Unpooled.buffer());
            s2cWholeBuf.writeJsonWithCodec(ItemStack.OPTIONAL_CODEC, stack.copy());
            s2cWholeBuf.writeUtf(player.getScoreboardName(), 32767);
            // Due to the bug in REI, no packets are actually sent here.
            /*
            userTransformer(player, CREATE_ITEMS_MESSAGE_PACKET, s2cWholeBuf, false, (id, s2cSplitBuf) -> {
                ProtocolUtils.sendPayloadPacket(player, new BufCustomPacketPayload(new CustomPacketPayload.Type<>(id), ByteBufUtil.getBytes(s2cSplitBuf)));
            });
            */
        };
        useTransformer(player, payload.id(), c2sBuf, true, consumer);
    }

    @ProtocolHandler.PayloadReceiver(payload = BufCustomPacketPayload.class, payloadId = "create_item_hotbar")
    public static void handleCreateItemHotbar(ServerPlayer player, BufCustomPacketPayload payload) {
        if (!LeavesConfig.protocol.reiServerProtocol || !hasCheatPermission(player)) {
            return;
        }
        RegistryFriendlyByteBuf c2sBuf = ProtocolUtils.decorate(Unpooled.buffer());
        c2sBuf.writeBytes(payload.payload());
        BiConsumer<ResourceLocation, RegistryFriendlyByteBuf> consumer = (ignored, c2sWholeBuf) -> {
            FriendlyByteBuf tmpBuf = new FriendlyByteBuf(Unpooled.buffer()).writeBytes(c2sWholeBuf.readByteArray());
            ItemStack stack = tmpBuf.readJsonWithCodec(ItemStack.OPTIONAL_CODEC);
            int hotbarSlotId = tmpBuf.readVarInt();
            if (hotbarSlotId >= 0 && hotbarSlotId < 9) {
                AbstractContainerMenu menu = player.containerMenu;
                player.getInventory().items.set(hotbarSlotId, stack.copy());
                menu.broadcastChanges();
                RegistryFriendlyByteBuf s2cWholeBuf = ProtocolUtils.decorate(Unpooled.buffer());
                s2cWholeBuf.writeJsonWithCodec(ItemStack.OPTIONAL_CODEC, stack.copy());
                s2cWholeBuf.writeUtf(player.getScoreboardName(), 32767);
                // Due to the bug in REI, no packets are actually sent here.
                /*
                userTransformer(player, CREATE_ITEMS_MESSAGE_PACKET, s2cWholeBuf, false, (id, s2cSplitBuf) -> {
                    ProtocolUtils.sendPayloadPacket(player, new BufCustomPacketPayload(new CustomPacketPayload.Type<>(id), ByteBufUtil.getBytes(s2cSplitBuf)));
                });
                */
            } else {
                player.displayClientMessage(Component.translatable("text.rei.failed_cheat_items"), false);
            }
        };
        useTransformer(player, payload.id(), c2sBuf, true, consumer);
    }

    private static void useTransformer(ServerPlayer player,
                                       ResourceLocation id,
                                       RegistryFriendlyByteBuf buf,
                                       boolean c2s,
                                       BiConsumer<ResourceLocation, RegistryFriendlyByteBuf> consumer) {
        PacketTransformer transformer = TRANSFORMERS.get(id);
        if (transformer != null) {
            if (c2s) {
                transformer.inbound(id, buf, player, consumer);
            } else {
                transformer.outbound(id, buf, consumer);
            }
        } else {
            consumer.accept(id, buf);
        }
    }

    private static boolean hasCheatPermission(ServerPlayer player) {
        if (player.getBukkitEntity().hasPermission(CHEAT_PERMISSION)) {
            return true;
        }
        player.displayClientMessage(Component.translatable("text.rei.no_permission_cheat").withStyle(ChatFormatting.RED), false);
        return false;
    }
}
