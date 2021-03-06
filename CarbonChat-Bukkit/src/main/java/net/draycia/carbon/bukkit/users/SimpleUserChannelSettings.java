package net.draycia.carbon.bukkit.users;

import net.draycia.carbon.api.CarbonChat;
import net.draycia.carbon.api.CarbonChatProvider;
import net.draycia.carbon.api.users.CarbonUser;
import net.draycia.carbon.api.users.UserChannelSettings;
import net.kyori.adventure.text.format.TextColor;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.UUID;

public class SimpleUserChannelSettings implements UserChannelSettings {

  private final transient @NonNull CarbonChat carbonChat = CarbonChatProvider.carbonChat();
  private boolean spying;
  private boolean ignored;
  private @Nullable String color;
  private @NonNull UUID uuid;
  private @NonNull String channel;

  @SuppressWarnings("initialization.fields.uninitialized")
  public SimpleUserChannelSettings(final @NonNull UUID uuid, final @NonNull String channel) {
    this.uuid = uuid;
    this.channel = channel;
  }

  private @NonNull CarbonUser user() {
    return this.carbonChat.userService().wrap(this.uuid);
  }

  @Override
  public boolean spying() {
    return this.spying;
  }

  @Override
  public void spying(final boolean spying, final boolean fromRemote) {
    this.spying = spying;

    if (!fromRemote) {
      this.carbonChat.messageService().sendMessage("spying-channel", this.uuid, byteArray -> {
        byteArray.writeUTF(this.channel);
        byteArray.writeBoolean(spying);
      });
    }
  }

  @Override
  public boolean ignored() {
    return this.ignored;
  }

  @Override
  public void ignoring(final boolean ignored, final boolean fromRemote) {
    this.ignored = ignored;

    if (!fromRemote) {
      this.carbonChat.messageService().sendMessage("ignoring-channel", this.uuid, byteArray -> {
        byteArray.writeUTF(this.channel);
        byteArray.writeBoolean(ignored);
      });
    }
  }

  @Override
  public @Nullable TextColor color() {
    if (this.color == null) {
      return null;
    }

    return TextColor.fromHexString(this.color);
  }

  @Override
  public void color(final @Nullable TextColor color, final boolean fromRemote) {
    if (color == null) {
      this.color = null;
    } else {
      this.color = color.asHexString();
    }

    if (!fromRemote) {
      final String newColor = this.color;

      if (newColor == null || newColor.isEmpty()) {
        this.carbonChat.messageService().sendMessage("channel-color-reset", this.uuid, byteArray -> {
        });
      } else {
        this.carbonChat.messageService().sendMessage("channel-color", this.uuid, byteArray -> {
          byteArray.writeUTF(newColor);
        });
      }
    }
  }

}
