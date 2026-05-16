package com.github.uright008.pp;

import com.github.uright008.pc.command.ParallelCommand;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PhysicsParallelization implements ModInitializer {
    public static final String MOD_ID = "physics-parallelization";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Physics Parallelization initializing...");
        PhysicsParallelConfig.ensureConfigSaved();

        // Register /parallel physics subcommand
        ParallelCommand.registerSubCommand(new PhysicsParallelCommand());
        LOGGER.info("Physics Parallelization ready — use /parallel physics");
    }
}
