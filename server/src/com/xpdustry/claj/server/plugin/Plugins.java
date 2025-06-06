package com.xpdustry.claj.server.plugin;

import com.xpdustry.claj.server.ClajVars;
import com.xpdustry.claj.server.util.JarLoader;

import arc.files.Fi;
import arc.files.ZipFi;
import arc.func.Cons;
import arc.func.Cons2;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.OrderedMap;
import arc.struct.OrderedSet;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.Strings;
import arc.util.Structs;
import arc.util.Time;
import arc.util.serialization.Json;
import arc.util.serialization.Jval;


/**
 * Simplified {@link mindustry.mod.Mods} that only handles plugins.
 */
public class Plugins {
  private static final String[] metaFiles = {"plugin.json", "plugin.hjson"};

  private PluginClassLoader mainLoader = new PluginClassLoader(getClass().getClassLoader());
  private Json json = new Json();
  
  /** Ordered plugins cache. Set to null to invalidate. */
  private @Nullable Seq<LoadedPlugin> lastOrderedPlugins = new Seq<>();
  private Seq<LoadedPlugin> plugins = new Seq<>();
  private ObjectMap<Class<?>, PluginMeta> metas = new ObjectMap<>();

  /** @return the main class loader for all plugins */
  public ClassLoader mainLoader() {
    return mainLoader;
  }

  /** Returns a file named 'config.json' in a special folder for the specified plugin.
   * Call this in init(). */
  public Fi getConfig(Plugin plugin) {
    PluginMeta load = metas.get(plugin.getClass());
    if(load == null) throw new IllegalArgumentException("Plugin is not loaded yet (or missing)!");
    return ClajVars.pluginsDirectory.child(load.name).child("config.json");
  }

  /** Returns a list of files per plugin subdirectory. */
  public void listFiles(String directory, Cons2<LoadedPlugin, Fi> cons) {
    eachEnabled(plugin -> {
      Fi file = plugin.root.child(directory);
      if (file.exists()) {
        for (Fi child : file.list()) 
          cons.get(plugin, child);
      }
    });
  }

  /** @return the loaded plugin found by name, or null if not found. */
  public @Nullable LoadedPlugin getPlugin(String name) {
    return plugins.find(m -> m.name.equals(name));
  }

  /** @return the loaded plugin found by class, or null if not found. */
  public @Nullable LoadedPlugin getPlugin(Class<? extends Plugin> type) {
    return plugins.find(m -> m.main != null && m.main.getClass() == type);
  }

  /** Loads all plugins from the folder, but does not call any methods on them.*/
  public void load() {
    Seq<Fi> candidates = new Seq<>();

    // Add local plugins
    Seq.with(ClajVars.pluginsDirectory.list())
       .retainAll(f -> f.extEquals("jar") || f.extEquals("zip") || (f.isDirectory() && Structs.contains(metaFiles, meta -> f.child(meta).exists())))
       .each(candidates::add);

    ObjectMap<String, Fi> mapping = new ObjectMap<>();
    Seq<PluginMeta> metas = new Seq<>();

    for (Fi file : candidates) {
      PluginMeta meta = null;

      try {
        Fi zip = file.isDirectory() ? file : new ZipFi(file);
        if(zip.list().length == 1 && zip.list()[0].isDirectory()) zip = zip.list()[0];
        meta = findMeta(zip);
      } catch (Throwable ignored) {}

      if (meta == null || meta.name == null) continue;
      metas.add(meta);
      mapping.put(meta.internalName, file);
    }

    OrderedMap<String, PluginState> resolved = resolveDependencies(metas);
    for (ObjectMap.Entry<String, PluginState> entry : resolved) {
      Fi file = mapping.get(entry.key);

      Log.debug("[Plugins] Loading plugin @", file);

      try {
        LoadedPlugin plugin = loadPlugin(file, false, entry.value == PluginState.enabled);
        plugin.state = entry.value;
        plugins.add(plugin);
        //invalidate ordered plugins cache
        lastOrderedPlugins = null;
        
      } catch (Throwable e) {
        Log.err("Failed to load plugin file @. Skipping.", file);
        Log.err(e);
      }
    }

    // Resolve the state
    plugins.each(this::updateDependencies);
    sortPlugins();
  }

  /** Sort plugins to make sure servers handle them properly and they appear correctly in the dialog */
  private void sortPlugins() {
    plugins.sort(Structs.comps(Structs.comparingInt(m -> m.state.ordinal()), Structs.comparing(m -> m.name)));
  }

  private void updateDependencies(LoadedPlugin plugin) {
    plugin.dependencies.clear();
    plugin.missingDependencies.clear();
    plugin.dependencies = plugin.meta.dependencies.map(this::locatePlugin);

    for (int i=0; i<plugin.dependencies.size; i++) {
      if (plugin.dependencies.get(i) == null)
        plugin.missingDependencies.add(plugin.meta.dependencies.get(i));
    }
  }

  /** @return plugins ordered in the correct way needed for dependencies. */
  public Seq<LoadedPlugin> orderedPlugins() {
    //update cache if it's "dirty"/empty
    if (lastOrderedPlugins == null) {
      //only enabled plugins participate; this state is resolved in load()
      Seq<LoadedPlugin> enabled = plugins.select(LoadedPlugin::enabled);

      ObjectMap<String, LoadedPlugin> mapping = enabled.asMap(m -> m.meta.internalName);
      lastOrderedPlugins = resolveDependencies(enabled.map(m -> m.meta)).orderedKeys().map(mapping::get);
    }
    return lastOrderedPlugins;
  }

  public LoadedPlugin locatePlugin(String name) {
    return plugins.find(plugin -> plugin.enabled() && plugin.name.equals(name));
  }

  /** @return a list of plugins and versions, in the format name:version. */
  public Seq<String> getPluginStrings() {
    return plugins.select(l -> l.enabled()).map(l -> l.name + ":" + l.meta.version);
  }

  /** @return the plugins that the client is missing.
   * The inputted array is changed to contain the extra plugins that the client has but the server doesn't.*/
  public Seq<String> getIncompatibility(Seq<String> out) {
    Seq<String> plugins = getPluginStrings();
    Seq<String> result = plugins.copy();
    for (String plugin : plugins) {
      if (out.remove(plugin)) result.remove(plugin);
    }
    return result;
  }

  public Seq<LoadedPlugin> list() {
    return plugins;
  }

  /** Iterates through each plugin with a main class. */
  public void eachClass(Cons<Plugin> cons) {
    orderedPlugins().each(p -> p.main != null, p -> contextRun(p, () -> cons.get(p.main)));
  }

  /** Iterates through each enabled plugin. */
  public void eachEnabled(Cons<LoadedPlugin> cons) {
    orderedPlugins().each(LoadedPlugin::enabled, cons);
  }

  public void contextRun(LoadedPlugin plugin, Runnable run) {
    try { run.run(); } 
    catch (Throwable t) { throw new RuntimeException("Error loading plugin " + plugin.meta.name, t); }
  }

  /** Tries to find the config file of a plugin. */
  public @Nullable PluginMeta findMeta(Fi file) {
    Fi metaFile = null;
    for (String name : metaFiles) {
      if ((metaFile = file.child(name)).exists()) break;
    }
    if (!metaFile.exists()) return null;

    PluginMeta meta = json.fromJson(PluginMeta.class, Jval.read(metaFile.readString()).toString(Jval.Jformat.plain));
    meta.cleanup();
    return meta;
  }

  /** Resolves the loading order of a list plugins using their internal names. */
  public OrderedMap<String, PluginState> resolveDependencies(Seq<PluginMeta> metas) {
    PluginResolutionContext context = new PluginResolutionContext();

    for (PluginMeta meta : metas) {
      Seq<PluginDependency> dependencies = new Seq<>();
      for (String dependency : meta.dependencies)
        dependencies.add(new PluginDependency(dependency, true));
      for (String dependency : meta.softDependencies)
        dependencies.add(new PluginDependency(dependency, false));
      context.dependencies.put(meta.internalName, dependencies);
    }

    for (String key : context.dependencies.keys()) {
      if(context.ordered.contains(key)) continue;
      resolve(key, context);
      context.visited.clear();
    }

    OrderedMap<String, PluginState> result = new OrderedMap<>();
    for (String name : context.ordered) result.put(name, PluginState.enabled);
    result.putAll(context.invalid);
    return result;
  }

  private boolean resolve(String element, PluginResolutionContext context) {
    context.visited.add(element);
    
    for (PluginDependency dependency : context.dependencies.get(element)) {
      // Circular dependencies ?
      if (context.visited.contains(dependency.name) && !context.ordered.contains(dependency.name)) {
        context.invalid.put(dependency.name, PluginState.circularDependencies);
        return false;
          
      // If dependency present, resolve it, or if it's not required, ignore it
      } else if (context.dependencies.containsKey(dependency.name)) {
        if (!context.ordered.contains(dependency.name) && !resolve(dependency.name, context) && dependency.required) {
          context.invalid.put(element, PluginState.incompleteDependencies);
          return false;
        }
          
      // The dependency is missing, but if not required, skip
      } else if (dependency.required) {
        context.invalid.put(element, PluginState.missingDependencies);
        return false;
      }
    }
    
    if (!context.ordered.contains(element)) context.ordered.add(element);
    return true;
  }

  /** Loads a plugin file+meta, but does not add it to the list.
   * Note that directories can be loaded as plugins. */
  private LoadedPlugin loadPlugin(Fi sourceFile, boolean overwrite, boolean initialize) throws Exception {
    Time.mark();

    Fi zip = sourceFile.isDirectory() ? sourceFile : new ZipFi(sourceFile);
    if (zip.list().length == 1 && zip.list()[0].isDirectory()) zip = zip.list()[0];

    PluginMeta meta = findMeta(zip);
    if (meta == null) {
      Log.warn("Plugin @ doesn't have a 'plugin.[h]json' file, skipping.", zip);
      throw new ModLoadException("Invalid file: No plugin.json found.");
    }

    String camelized = meta.name.replace(" ", "");
    String mainClass = meta.main == null ? camelized.toLowerCase() + "." + camelized + "Plugin" : meta.main;
    String baseName = meta.name.toLowerCase().replace(" ", "-");

    LoadedPlugin other = plugins.find(m -> m.name.equals(baseName));

    if (other != null) {
      if (overwrite) {
        //close zip file
        if (other.root instanceof ZipFi) other.root.delete();
        
        //delete the old plugin directory
        if (other.file.isDirectory()) other.file.deleteDirectory();
        else other.file.delete();

        //unload
        plugins.remove(other);
        
      } else throw new ModLoadException("A plugin with the name '" + baseName + "' is already imported.");
    }

    ClassLoader loader = null;
    Plugin mainMod;
    Fi mainFile = zip;

    String[] path = (mainClass.replace('.', '/') + ".class").split("/");
    for (String str : path) {
      if(!str.isEmpty()) mainFile = mainFile.child(str);
    }

    //make sure the main class exists before loading it; if it doesn't just don't put it there
    //if the plugin is explicitly marked as java, try loading it anyway
    if (mainFile.exists() && initialize) {
      loader = JarLoader.load(sourceFile, mainLoader);
      mainLoader.addChild(loader);
      Class<?> main = Class.forName(mainClass, true, loader);

      metas.put(main, meta);
      mainMod = (Plugin)main.getDeclaredConstructor().newInstance();
    } else mainMod = null;
    
    //disallow putting a description after the version
    if (meta.version != null) {
      int line = meta.version.indexOf('\n');
      if(line != -1) meta.version = meta.version.substring(0, line);
    }

    Log.info("Loaded plugin '@' in @ms", meta.name, Time.elapsed());
    return new LoadedPlugin(sourceFile, zip, mainMod, loader, meta);
  }

  /** Represents a plugin's state. May be a jar file, folder or zip. */
  public static class LoadedPlugin {
    /** The location of this plugin's zip file/folder on the disk. */
    public final Fi file;
    /** The root zip file; points to the contents of this plugin. In the case of folders, this is the same as the plugin's file. */
    public final Fi root;
    /** The plugin's main class. */
    public final Plugin main;
    /** Internal plugin name. Used for textures. */
    public final String name;
    /** This plugin's metadata. */
    public final PluginMeta meta;
    /** This plugin's dependencies as already-loaded plugins. */
    public Seq<LoadedPlugin> dependencies = new Seq<>();
    /** All missing dependencies of this plugin as strings. */
    public Seq<String> missingDependencies = new Seq<>();
    /** Current state of this plugin. */
    public PluginState state = PluginState.enabled;
    /** Class loader for JAR plugins. */
    public ClassLoader loader;

    public LoadedPlugin(Fi file, Fi root, Plugin main, ClassLoader loader, PluginMeta meta) {
      this.root = root;
      this.file = file;
      this.loader = loader;
      this.main = main;
      this.meta = meta;
      this.name = meta.name.toLowerCase().replace(" ", "-");
    }

    @Nullable
    public String getRepo() {
      return meta.repo;
    }

    public boolean enabled() {
      return state == PluginState.enabled;
    }

    public boolean hasUnmetDependencies() {
      return !missingDependencies.isEmpty();
    }

    @Override
    public String toString() {
      return "LoadedMod{" +
             "file=" + file +
             ", root=" + root +
             ", name='" + name + '\'' +
             '}';
    }
  }

  /** Plugin metadata information.*/
  public static class PluginMeta {
    /** Name as defined in plugin.json. Stripped of colors, but may contain spaces. */
    public String name;
    /** Name without spaces in all lower case. */
    public String internalName;
    public @Nullable String displayName, author, description, version, repo;
    /** Plugin's main class. */
    public String main;
    public Seq<String> dependencies = new Seq<>();
    public Seq<String> softDependencies = new Seq<>();

    //removes all colors
    public void cleanup() {
      if(name != null) name = Strings.stripColors(name);
      if(displayName != null) displayName = Strings.stripColors(displayName);
      if(displayName == null) displayName = name;
      if(author != null) author = Strings.stripColors(author);
      if(description != null) description = Strings.stripColors(description);
      if(name != null) internalName = name.toLowerCase().replace(" ", "-");
    }

    @Override
    public String toString() {
      return "ModMeta{" +
             "name='" + name + '\'' +
             ", displayName='" + displayName + '\'' +
             ", author='" + author + '\'' +
             ", description='" + description + '\'' +
             ", version='" + version + '\'' +
             ", main='" + main + '\'' +
             ", repo='" + repo + '\'' +
             ", dependencies=" + dependencies +
             ", softDependencies=" + softDependencies +
             '}';
    }
  }

  @SuppressWarnings("serial")
  public static class ModLoadException extends RuntimeException {
    public ModLoadException(String message) {
      super(message);
    }
  }

  public enum PluginState{
    enabled,
    missingDependencies,
    incompleteDependencies,
    circularDependencies,
  }

  public static class PluginResolutionContext {
    public final ObjectMap<String, Seq<PluginDependency>> dependencies = new ObjectMap<>();
    public final ObjectSet<String> visited = new ObjectSet<>();
    public final OrderedSet<String> ordered = new OrderedSet<>();
    public final ObjectMap<String, PluginState> invalid = new OrderedMap<>();
  }

  public static final class PluginDependency{
    public final String name;
    public final boolean required;

    public PluginDependency(String name, boolean required){
      this.name = name;
      this.required = required;
    }
  }
}
