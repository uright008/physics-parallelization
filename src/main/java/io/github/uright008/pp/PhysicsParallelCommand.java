package io.github.uright008.pp;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.uright008.pc.ParallelThreadPool;
import io.github.uright008.pc.command.ParallelSubCommand;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class PhysicsParallelCommand implements ParallelSubCommand {

    @Override
    public String getName() {
        return "physics";
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSourceStack> builder) {
        builder
                .executes(this::showStatus)
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(this::setEnabled))
                .then(Commands.literal("reload")
                        .executes(this::reloadConfig));
    }

    @Override
    public String getStatusLine() {
        boolean on = PhysicsParallelConfig.isEnabled();
        return "§7  Physics:   " + (on ? "§aON" : "§cOFF")
                + " §7pool=" + ParallelThreadPool.getParallelism();
    }

    private int showStatus(CommandContext<CommandSourceStack> ctx) {
        int poolSize = ParallelThreadPool.getParallelism();
        Component msg = Component.literal(
                "§e/parallel physics\n" +
                "§7  Status:     " + (PhysicsParallelConfig.isEnabled() ? "§aON" : "§cOFF") + "\n" +
                "§7  ThreadPool: §a" + poolSize + " workers\n" +
                "§7Usage: /parallel physics [on|off|reload]"
        );
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private int setEnabled(CommandContext<CommandSourceStack> ctx) {
        boolean enabled = BoolArgumentType.getBool(ctx, "enabled");
        PhysicsParallelConfig.setEnabled(enabled);
        Component msg = Component.literal("§aParallel entity physics is now " + (enabled ? "§eON" : "§cOFF"));
        ctx.getSource().sendSuccess(() -> msg, true);
        return 1;
    }

    private int reloadConfig(CommandContext<CommandSourceStack> ctx) {
        PhysicsParallelConfig.reloadConfig();
        Component msg = Component.literal("§aPhysics config reloaded.");
        ctx.getSource().sendSuccess(() -> msg, true);
        return 1;
    }
}
