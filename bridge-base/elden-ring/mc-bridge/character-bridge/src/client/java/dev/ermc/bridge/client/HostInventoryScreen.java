package dev.ermc.bridge.client;

import dev.ermc.bridge.link.ErLink;
import dev.ermc.bridge.link.HostInventorySample;
import dev.ermc.bridge.link.HostInventorySample.Entry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** A read-only view of the local host's carried items; it never sends inventory mutations. */
public final class HostInventoryScreen extends Screen {
    private HostInventorySample sample;
    private List<Entry> visible = List.of();
    private EditBox search;
    private Button filterButton, previous, next;
    private int filter, page, pageSize = 8, panelX, panelWidth, rowsY;
    private long lastRead;
    private String query = "", status = "等待法环背包";

    public HostInventoryScreen() { super(Component.literal("法环背包 · 只读")); }

    @Override protected void init() {
        panelWidth = Math.min(600, width - 20);
        panelX = (width - panelWidth) / 2;
        rowsY = 86;
        pageSize = Math.max(1, (height - rowsY - 60) / 18);
        search = addRenderableWidget(new EditBox(font, panelX + 12, 48, Math.max(60, panelWidth - 136), 20,
            Component.literal("搜索物品 ID、类别或装备槽")));
        search.setMaxLength(80);
        search.setValue(query);
        search.setHint(Component.literal("搜索 ID / 类别 / 装备槽"));
        search.setResponder(value -> { query = value; page = 0; refilter(); });
        filterButton = addRenderableWidget(Button.builder(Component.literal(filterLabel()), button -> {
            filter = (filter + 1) % 3; button.setMessage(Component.literal(filterLabel())); page = 0; refilter();
        }).bounds(panelX + panelWidth - 112, 48, 100, 20).build());
        previous = addRenderableWidget(Button.builder(Component.literal("上一页"), button -> { page--; refilter(); })
            .bounds(panelX + 12, height - 35, 64, 20).build());
        next = addRenderableWidget(Button.builder(Component.literal("下一页"), button -> { page++; refilter(); })
            .bounds(panelX + 82, height - 35, 64, 20).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), button -> onClose())
            .bounds(panelX + panelWidth - 76, height - 35, 64, 20).build());
        setInitialFocus(search);
        readSnapshot();
    }

    private String filterLabel() { return switch (filter) { case 1 -> "已装备"; case 2 -> "关键物品"; default -> "全部物品"; }; }

    private void refilter() {
        visible = sample == null ? List.of() : sample.entries().stream()
            .filter(e -> filter == 0 || filter == 1 && e.equipmentMask() != 0 || filter == 2 && e.keyItem())
            .filter(e -> e.matches(query)).toList();
        page = Math.max(0, Math.min(page, Math.max(0, (visible.size() - 1) / pageSize)));
        if (previous != null) previous.active = page > 0;
        if (next != null) next.active = (page + 1) * pageSize < visible.size();
    }

    private void readSnapshot() {
        lastRead = System.currentTimeMillis();
        sample = null;
        ErLink link = ErLink.get();
        if (!link.alive()) status = "法环连接已断开";
        else {
            var inventory = link.readHostInventory();
            var owner = link.readHostProgression();
            if (inventory.isEmpty()) status = "背包读取不可用，请检查配套桥接版本";
            else if (inventory.get().state() == HostInventorySample.LOADING) status = "法环正在加载，等待角色就绪";
            else if (inventory.get().state() != HostInventorySample.READY) status = "背包读取不可用，等待有效角色";
            else if (owner.isEmpty() || !inventory.get().currentFor(owner.get(), link.hostFrame(), link.hostLife(), link.hostStartMs(), lastRead))
                status = "等待当前角色的最新背包";
            else {
                sample = inventory.get();
                status = "携带物品 " + sample.totalEntries() + " 项"
                    + ((sample.flags() & HostInventorySample.TRUNCATED) != 0 ? " · 仅显示前 " + sample.entries().size() + " 项" : "");
            }
        }
        refilter();
    }

    @Override public void tick() {
        if (minecraft == null || minecraft.player == null || !Overlay.active()) { onClose(); return; }
        long now = System.currentTimeMillis();
        if (now < lastRead || now - lastRead >= 250) readSnapshot();
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Keep the host scene visible around the panel; do not add a full-screen blur pass.
        graphics.fill(panelX, 10, panelX + panelWidth, height - 8, 0xE6161B23);
        graphics.drawString(font, title, panelX + 12, 19, 0xFFF2D49C, false);
        graphics.drawString(font, status, panelX + 12, 33, 0xFFB8C8DD, false);
        graphics.drawString(font, "类别 / 原始物品 ID", panelX + 12, 74, 0xFF93A5BB, false);
        graphics.drawString(font, "数量", panelX + panelWidth / 2, 74, 0xFF93A5BB, false);
        graphics.drawString(font, "已装备位置", panelX + panelWidth * 2 / 3, 74, 0xFF93A5BB, false);
        for (int row = 0; row < pageSize && page * pageSize + row < visible.size(); row++) {
            Entry entry = visible.get(page * pageSize + row);
            int y = rowsY + row * 18;
            if ((row & 1) == 0) graphics.fill(panelX + 8, y - 2, panelX + panelWidth - 8, y + 15, 0x50394656);
            String label = entry.category() + "  " + entry.itemCode();
            graphics.drawString(font, font.plainSubstrByWidth(label, panelWidth / 2 - 20), panelX + 12, y, 0xFFE7EDF5, false);
            graphics.drawString(font, Integer.toString(entry.quantity()), panelX + panelWidth / 2, y, 0xFFE7EDF5, false);
            String equipment = entry.equipmentText();
            graphics.drawString(font, font.plainSubstrByWidth(equipment.isEmpty() ? "—" : equipment, panelWidth / 3 - 16),
                panelX + panelWidth * 2 / 3, y, 0xFFB5D8BE, false);
            if (mouseX >= panelX + 8 && mouseX <= panelX + panelWidth - 8 && mouseY >= y - 2 && mouseY < y + 16) {
                graphics.renderTooltip(font, Component.literal(entry.category() + " " + entry.itemCode()
                    + " · ID " + Integer.toUnsignedString(entry.itemId()) + " · 槽 " + entry.slot()
                    + (entry.keyItem() ? " · 关键物品" : "") + (equipment.isEmpty() ? "" : " · " + equipment)), mouseX, mouseY);
            }
        }
        if (sample != null && visible.isEmpty()) graphics.drawString(font, query.isEmpty() ? "当前筛选下没有物品" : "没有匹配的物品", panelX + 12, rowsY + 10, 0xFFB8C8DD, false);
        graphics.drawString(font, font.plainSubstrByWidth("以物品 ID 显示 · 仓库不在此页 · 装备和使用请在法环完成", panelWidth - 24),
            panelX + 12, height - 49, 0xFF93A5BB, false);
        graphics.drawCenteredString(font, (page + 1) + " / " + Math.max(1, (visible.size() + pageSize - 1) / pageSize), width / 2, height - 28, 0xFFB8C8DD);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
