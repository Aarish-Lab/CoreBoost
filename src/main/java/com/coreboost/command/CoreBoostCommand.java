package com.coreboost.command;

import com.coreboost.client.ClientRuntime;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import java.util.List;

public final class CoreBoostCommand extends CommandBase {
    public String getCommandName() { return "coreboost"; }
    public String getCommandUsage(ICommandSender sender) {
        return "/coreboost [status|culling|particles|hud|fastmath] [on|off|toggle]";
    }
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            for (String line : ClientRuntime.status())
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + "[CoreBoost] " + EnumChatFormatting.GRAY + line));
            return;
        }
        String key = args[0].toLowerCase();
        if ("cull".equals(key)) key = "culling";
        if ("particle".equals(key)) key = "particles";
        if ("math".equals(key)) key = "fastmath";
        if (!"culling".equals(key) && !"particles".equals(key) && !"hud".equals(key) && !"fastmath".equals(key)) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + getCommandUsage(sender)));
            return;
        }
        String mode = args.length > 1 ? args[1].toLowerCase() : "toggle";
        boolean current = "culling".equals(key) ? ClientRuntime.culling : "particles".equals(key)
            ? ClientRuntime.particles : "hud".equals(key) ? ClientRuntime.hud : ClientRuntime.fastMath;
        boolean value;
        if ("on".equals(mode)) value = true;
        else if ("off".equals(mode)) value = false;
        else if ("toggle".equals(mode)) value = !current;
        else {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + getCommandUsage(sender)));
            return;
        }
        ClientRuntime.setOption(key, value);
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + "[CoreBoost] " + key
            + (value ? " enabled" : " disabled")));
    }
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, "status", "culling", "particles", "hud", "fastmath");
        if (args.length == 2) return getListOfStringsMatchingLastWord(args, "on", "off", "toggle");
        return null;
    }
}

