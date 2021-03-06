package net.draycia.carbon.common.users;

import co.aikar.idb.DatabaseOptions;
import co.aikar.idb.PooledDatabaseOptions;
import net.draycia.carbon.api.CarbonChat;
import net.draycia.carbon.api.channels.ChatChannel;
import net.draycia.carbon.api.users.ConsoleUser;
import net.draycia.carbon.api.users.PlayerUser;
import net.draycia.carbon.api.users.UserChannelSettings;
import co.aikar.idb.DB;
import co.aikar.idb.Database;
import co.aikar.idb.DbRow;
import co.aikar.idb.DbStatement;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalNotification;
import net.draycia.carbon.api.users.UserService;
import net.draycia.carbon.api.config.SQLCredentials;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.format.TextColor;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;

public class MySQLUserService<T extends PlayerUser, C extends ConsoleUser> implements UserService<T> {

  private final @NonNull CarbonChat carbonChat;
  private final @NonNull Database database;
  private final @NonNull Supplier<@NonNull Collection<@NonNull T>> supplier;
  private final @NonNull Supplier<@NonNull Collection<@NonNull String>> nameSupplier;
  private final @NonNull Function<UUID, T> userFactory;
  private final @NonNull Supplier<C> consoleFactory;

  @SuppressWarnings("methodref.receiver.bound.invalid")
  private final @NonNull LoadingCache<@NonNull UUID, @NonNull T> userCache = CacheBuilder.newBuilder()
    .removalListener(this::saveUser)
    .build(CacheLoader.from(this::loadUser));

  public MySQLUserService(final @NonNull CarbonChat carbonChat, final @NonNull SQLCredentials credentials,
                          final @NonNull Supplier<@NonNull Collection<@NonNull T>> supplier,
                          final @NonNull Supplier<@NonNull Collection<@NonNull String>> nameSupplier,
                          final @NonNull Function<UUID, T> userFactory,
                          final @NonNull Supplier<C> consoleFactory) {
    this.carbonChat = carbonChat;
    this.supplier = supplier;
    this.nameSupplier = nameSupplier;
    this.userFactory = userFactory;
    this.consoleFactory = consoleFactory;

    final String username = credentials.username();
    final String password = credentials.password();
    final String database = credentials.database();
    final String host = credentials.host();
    final int port = credentials.port();

    final String hostAndPort = host + ":" + port;

    final DatabaseOptions options = DatabaseOptions.builder().mysql(username, password, database, hostAndPort).build();
    this.database = PooledDatabaseOptions.builder().options(options).createHikariDatabase();

    DB.setGlobalDatabase(this.database);

    try {
      this.database.executeUpdate("CREATE TABLE IF NOT EXISTS sc_users (uuid CHAR(36) PRIMARY KEY," +
        "channel VARCHAR(16), muted BOOLEAN, shadowmuted BOOLEAN, spyingwhispers BOOLEAN," +
        "nickname VARCHAR(512), customchatcolor VARCHAR(32), whisperpingkey VARCHAR(64), " +
        "whisperpingvolume FLOAT, whisperpingpitch FLOAT, channelpingkey VARCHAR(64), " +
        "channelpingvolume FLOAT, channelpingpitch FLOAT)");

      // Ignore the exception, it's just saying the column already exists
      try {
        this.database.executeUpdate("ALTER TABLE sc_users ADD COLUMN " +
          "spyingwhispers BOOLEAN DEFAULT false, " +
          "nickname VARCHAR(512) DEFAULT false, " +
          "customchatcolor VARCHAR(32) DEFAULT false, " +
          "whisperpingkey VARCHAR(64) DEFAULT false, " +
          "whisperpingvolume FLOAT DEFAULT false, " +
          "whisperpingpitch FLOAT DEFAULT false, " +
          "channelpingkey VARCHAR(64) DEFAULT false, " +
          "channelpingvolume FLOAT DEFAULT false, " +
          "channelpingpitch FLOAT DEFAULT false");
      } catch (final SQLSyntaxErrorException ignored) {
      }

      this.database.executeUpdate("CREATE TABLE IF NOT EXISTS sc_channel_settings (uuid CHAR(36), channel CHAR(16), spying BOOLEAN, ignored BOOLEAN, color TINYTEXT, PRIMARY KEY (uuid, channel))");

      this.database.executeUpdate("CREATE TABLE IF NOT EXISTS sc_ignored_users (uuid CHAR(36), user CHAR(36), PRIMARY KEY (uuid, user))");
    } catch (final SQLException exception) {
      exception.printStackTrace();
    }

    final TimerTask timerTask = new TimerTask() {
      @Override
      public void run() {
        MySQLUserService.this.userCache.cleanUp();
      }
    };

    new Timer().schedule(timerTask, 0L, 300000L);
  }

  @Override
  public void onDisable() {
    this.userCache.invalidateAll();
    this.userCache.cleanUp();
    this.database.close();
  }

  @Override
  public @NonNull T wrap(final @NonNull UUID uuid) {
    try {
      return this.userCache.get(uuid);
    } catch (final ExecutionException exception) {
      throw new IllegalStateException(exception);
    }
  }

  @Override
  public @NonNull CompletableFuture<T> wrapLater(final @NonNull UUID uuid) {
    return CompletableFuture.supplyAsync(() -> this.wrap(uuid));
  }

  @Override
  public @Nullable C consoleUser() {
    return this.consoleFactory.get();
  }

  @Override
  public @Nullable T wrapIfLoaded(final @NonNull UUID uuid) {
    return this.userCache.getIfPresent(uuid);
  }

  @Override
  public @Nullable T refreshUser(final @NonNull UUID uuid) {
    this.userCache.invalidate(uuid);

    return this.wrap(uuid);
  }

  @Override
  public void invalidate(final @NonNull T user) {
    this.userCache.invalidate(user.uuid());
  }

  @Override
  public void validate(final @NonNull T user) {
    this.userCache.put(user.uuid(), user);
  }

  @Override
  public @NonNull Collection<@NonNull T> onlineUsers() {
    return this.supplier.get();
  }

  @Override
  public @NonNull Collection<@NonNull String> proxyPlayers() {
    return this.nameSupplier.get();
  }

  private @Nullable T loadUser(final @NonNull UUID uuid) {
    final T user = this.userFactory.apply(uuid);

    try (final DbStatement statement = this.database.query("SELECT * from sc_users WHERE uuid = ?;")) {
      statement.execute(uuid.toString());

      final DbRow users = statement.getNextRow();

      if (users == null) {
        return user;
      }

      final List<DbRow> channelSettings = this.database.getResults("SELECT * from sc_channel_settings WHERE uuid = ?;", uuid.toString());
      final List<DbRow> ignoredUsers = this.database.getResults("SELECT * from sc_ignored_users WHERE uuid = ?;", uuid.toString());

      final ChatChannel channel = this.carbonChat.channelRegistry().getOrDefault(users.getString("channel"));

      user.selectedChannel(channel, true);

      final String nickname = users.getString("nickname");

      if (nickname != null) {
        user.nickname(nickname, true);
      }

      user.muted(users.get("muted"), true);
      user.shadowMuted(users.get("shadowmuted"), true);
      user.spyingWhispers(users.get("spyingwhispers"), true);
      user.customChatColor(users.get("customchatcolor"), true);

      final String whisperPingKey = users.getString("whisperpingkey");
      final Float whisperPingVolume = users.getFloat("whisperpingvolume");
      final Float whisperPingPitch = users.getFloat("whisperpingpitch");

      final Sound whisperSound;

      if (whisperPingKey != null && whisperPingVolume != null && whisperPingPitch != null) {
        whisperSound = Sound.sound(Key.key(whisperPingKey), Sound.Source.PLAYER, whisperPingVolume, whisperPingPitch);
      } else {
        whisperSound = null;
      }

      final String channelPingKey = users.getString("channelpingkey");
      final Float channelPingVolume = users.getFloat("channelpingvolume");
      final Float channelPingPitch = users.getFloat("channelpingpitch");

      final Sound channelSound;

      if (channelPingKey != null && channelPingVolume != null && channelPingPitch != null) {
        channelSound = Sound.sound(Key.key(channelPingKey), Sound.Source.PLAYER, channelPingVolume, channelPingPitch);
      } else {
        channelSound = null;
      }

      user.pingOptions(new PlayerUser.PingOptions(whisperSound, channelSound));

      for (final DbRow channelSetting : channelSettings) {
        final ChatChannel chatChannel = this.carbonChat.channelRegistry().get(channelSetting.getString("channel"));

        if (chatChannel != null) {
          final UserChannelSettings settings = user.channelSettings(chatChannel);

          settings.spying(channelSetting.<Boolean>get("spying"), true);
          settings.ignoring(channelSetting.<Boolean>get("ignored"), true);

          final String color = channelSetting.getString("color");

          if (color != null) {
            settings.color(TextColor.fromHexString(color), true);
          }
        }
      }

      for (final DbRow ignoredUser : ignoredUsers) {
        user.ignoringUser(UUID.fromString(ignoredUser.getString("user")), true, true);
      }
    } catch (final SQLException exception) {
      exception.printStackTrace();
    }

    return user;
  }

  @SuppressWarnings("argument.type.incompatible")
  private void saveUser(final @NonNull RemovalNotification<@NonNull UUID, @NonNull T> notification) {
    final T user = notification.getValue();

    this.database.createTransaction(stm -> {
      // Save user general data
      String selectedName = null;

      final ChatChannel channel = user.selectedChannel();

      if (channel != null) {
        selectedName = channel.key();
      }

      final Sound whisperSound = user.pingOptions().whisperSound();
      final String whisperName;
      final Float whisperVolume;
      final Float whisperPitch;

      if (whisperSound != null) {
        whisperName = whisperSound.name().asString();
        whisperVolume = whisperSound.volume();
        whisperPitch = whisperSound.pitch();
      } else {
        whisperName = null;
        whisperVolume = null;
        whisperPitch = null;
      }

      final Sound channelSound = user.pingOptions().pingSound();
      final String channelName;
      final Float channelVolume;
      final Float channelPitch;

      if (channelSound != null) {
        channelName = channelSound.name().asString();
        channelVolume = channelSound.volume();
        channelPitch = channelSound.pitch();
      } else {
        channelName = null;
        channelVolume = null;
        channelPitch = null;
      }

      this.carbonChat.logger().info("Saving user data!");
      stm.executeUpdateQuery("INSERT INTO sc_users (uuid, channel, muted, shadowmuted, spyingwhispers, nickname, customchatcolor, whisperpingkey, whisperpingvolume " +
          "whisperpingpitch, channelpingkey, channelpingvolume, channelpingpitch) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
          "ON DUPLICATE KEY UPDATE channel = ?, muted = ?, shadowmuted = ?, spyingwhispers = ?, nickname = ?, customchatcolor = ?, whisperpingkey = ?, whisperpingvolume = ?, " +
          "whisperpingpitch = ?, channelpingkey = ?, channelpingvolume = ?, channelpingpitch = ?",
        user.uuid().toString(),
        selectedName, user.muted(), user.shadowMuted(), user.spyingWhispers(), user.nickname(), user.customChatColor(),
        whisperName, whisperVolume, whisperPitch, channelName, channelVolume, channelPitch,
        selectedName, user.muted(), user.shadowMuted(), user.spyingWhispers(), user.nickname(), user.customChatColor(),
        whisperName, whisperVolume, whisperPitch, channelName, channelVolume, channelPitch);

      this.carbonChat.logger().info("Saving user channel settings!");
      // Save user channel settings
      for (final Map.Entry<String, ? extends UserChannelSettings> entry : user.channelSettings().entrySet()) {
        final UserChannelSettings value = entry.getValue();

        String colorString = null;
        final TextColor color = value.color();

        if (color != null) {
          colorString = color.asHexString();
        }

        stm.executeUpdateQuery("INSERT INTO sc_channel_settings (uuid, channel, spying, ignored, color) VALUES (?, ?, ?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE spying = ?, ignored = ?, color = ?",
          user.uuid().toString(), entry.getKey(), value.spying(), value.ignored(), colorString,
          value.spying(), value.ignored(), colorString);
      }

      this.carbonChat.logger().info("Saving user ignores!");
      // Save user ignore list (remove old entries then add new ones)
      // TODO: keep DB up to date with settings as settings are mutated
      stm.executeUpdateQuery("DELETE FROM sc_ignored_users WHERE uuid = ?", user.uuid().toString());

      for (final UUID entry : user.ignoredUsers()) {
        stm.executeUpdateQuery("INSERT INTO sc_ignored_users (uuid, user) VALUES (?, ?)",
          user.uuid().toString(), entry.toString());
      }

      return true;
    });
  }

}
