// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.configurationStore

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.components.StateStorageChooserEx.Resolution
import com.intellij.openapi.roots.ProjectModelElement
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.util.ReflectionUtil
import com.intellij.util.SmartList
import com.intellij.util.ThreeState
import com.intellij.util.io.systemIndependentPath
import org.jdom.Element
import org.jetbrains.annotations.TestOnly
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * If componentManager not specified, storage will not add file tracker
 */
open class StateStorageManagerImpl(private val rootTagName: String,
                                   final override val macroSubstitutor: PathMacroSubstitutor? = null,
                                   override val componentManager: ComponentManager? = null,
                                   private val virtualFileTracker: StorageVirtualFileTracker? = createDefaultVirtualTracker(componentManager)) : StateStorageManager {
  @Volatile
  protected var macros: List<Macro> = emptyList()

  protected val storageLock = ReentrantReadWriteLock()
  private val storages = HashMap<String, StateStorage>()

  val compoundStreamProvider: CompoundStreamProvider = CompoundStreamProvider()

  val isStreamProviderPreventExportAction: Boolean
    get() = compoundStreamProvider.providers.any { it.isDisableExportAction }

  override fun addStreamProvider(provider: StreamProvider, first: Boolean) {
    if (first) {
      compoundStreamProvider.providers.add(0, provider)
    }
    else {
      compoundStreamProvider.providers.add(provider)
    }
  }

  override fun removeStreamProvider(clazz: Class<out StreamProvider>) {
    compoundStreamProvider.providers.removeAll { clazz.isInstance(it) }
  }

  // access under storageLock
  @Suppress("LeakingThis")
  private var isUseVfsListener = when (componentManager) {
    null, is Application -> ThreeState.NO
    else -> ThreeState.UNSURE // unsure because depends on stream provider state
  }

  open fun getFileBasedStorageConfiguration(fileSpec: String) = defaultFileBasedStorageConfiguration

  protected open val isUseXmlProlog: Boolean
    get() = true

  companion object {
    private fun createDefaultVirtualTracker(componentManager: ComponentManager?): StorageVirtualFileTracker? {
      return when (componentManager) {
        null -> {
          null
        }
        is Application -> {
          StorageVirtualFileTracker(componentManager.messageBus)
        }
        else -> {
          val tracker = (ApplicationManager.getApplication().stateStore.storageManager as? StateStorageManagerImpl)?.virtualFileTracker
                        ?: return null
          Disposer.register(componentManager, Disposable {
            tracker.remove { it.storageManager.componentManager == componentManager }
          })
          tracker
        }
      }
    }
  }

  @TestOnly
  fun getVirtualFileTracker() = virtualFileTracker

  /**
   * Returns old map.
   */
  fun setMacros(map: List<Macro>): List<Macro> {
    val oldValue = macros
    macros = map
    return oldValue
  }

  @Suppress("CAST_NEVER_SUCCEEDS")
  final override fun getStateStorage(storageSpec: Storage) = getOrCreateStorage(
    storageSpec.path,
    storageSpec.roamingType,
    storageSpec.storageClass.java,
    storageSpec.stateSplitter.java,
    storageSpec.exclusive,
    storageCreator = storageSpec as? StorageCreator
  )

  protected open fun normalizeFileSpec(fileSpec: String): String {
    val path = FileUtilRt.toSystemIndependentName(fileSpec)
    // fileSpec for directory based storage could be erroneously specified as "name/"
    return if (path.endsWith('/')) path.substring(0, path.length - 1) else path
  }

  // storageCustomizer - to ensure that other threads will use fully constructed and configured storage (invoked under the same lock as created)
  fun getOrCreateStorage(collapsedPath: String,
                         roamingType: RoamingType = RoamingType.DEFAULT,
                         storageClass: Class<out StateStorage> = StateStorage::class.java,
                         @Suppress("DEPRECATION") stateSplitter: Class<out StateSplitter> = StateSplitterEx::class.java,
                         exclusive: Boolean = false,
                         storageCustomizer: (StateStorage.() -> Unit)? = null,
                         storageCreator: StorageCreator? = null): StateStorage {
    val normalizedCollapsedPath = normalizeFileSpec(collapsedPath)
    val key = computeStorageKey(storageClass, normalizedCollapsedPath, collapsedPath, storageCreator)
    val storage = storageLock.read { storages.get(key) } ?: return storageLock.write {
      storages.getOrPut(key) {
        val storage = when (storageCreator) {
          null -> createStateStorage(storageClass, normalizedCollapsedPath, roamingType, stateSplitter, exclusive)
          else -> storageCreator.create(this)
        }
        storageCustomizer?.let { storage.it() }
        storage
      }
    }

    storageCustomizer?.let { storage.it() }
    return storage
  }

  private fun computeStorageKey(storageClass: Class<out StateStorage>, normalizedCollapsedPath: String, collapsedPath: String, storageCreator: StorageCreator?): String {
    if (storageClass != StateStorage::class.java) {
      return storageClass.name
    }
    if (normalizedCollapsedPath.isEmpty()) {
      throw Exception("Normalized path is empty, raw path '$collapsedPath'")
    }
    return storageCreator?.key ?: normalizedCollapsedPath
  }

  fun getCachedFileStorages(): Set<StateStorage> = storageLock.read { storages.values.toSet() }

  fun findCachedFileStorage(name: String): StateStorage? = storageLock.read { storages.get(name) }

  fun getCachedFileStorages(changed: Collection<String>,
                            deleted: Collection<String>,
                            pathNormalizer: ((String) -> String)? = null): Pair<Collection<FileBasedStorage>, Collection<FileBasedStorage>> = storageLock.read {
    Pair(getCachedFileStorages(changed, pathNormalizer), getCachedFileStorages(deleted, pathNormalizer))
  }

  fun updatePath(spec: String, newPath: Path) {
    val storage = getCachedFileStorages(listOf(spec)).firstOrNull() ?: return
    if (storage is StorageVirtualFileTracker.TrackedStorage) {
      virtualFileTracker?.let { tracker ->
        tracker.remove(storage.file.systemIndependentPath)
        tracker.put(newPath.systemIndependentPath, storage)
      }
    }
    storage.setFile(null, newPath)
  }

  fun getCachedFileStorages(fileSpecs: Collection<String>, pathNormalizer: ((String) -> String)? = null): Collection<FileBasedStorage> {
    if (fileSpecs.isEmpty()) {
      return emptyList()
    }

    storageLock.read {
      var result: MutableList<FileBasedStorage>? = null
      for (fileSpec in fileSpecs) {
        val path = normalizeFileSpec(pathNormalizer?.invoke(fileSpec) ?: fileSpec)
        val storage = storages.get(path)
        if (storage is FileBasedStorage) {
          if (result == null) {
            result = SmartList()
          }
          result.add(storage)
        }
      }
      return result ?: emptyList()
    }
  }

  // overridden in upsource
  protected open fun createStateStorage(storageClass: Class<out StateStorage>,
                                        collapsedPath: String,
                                        roamingType: RoamingType,
                                        @Suppress("DEPRECATION") stateSplitter: Class<out StateSplitter>,
                                        exclusive: Boolean = false): StateStorage {
    if (storageClass != StateStorage::class.java) {
      val constructor = storageClass.constructors.first { it.parameterCount <= 3 }
      constructor.isAccessible = true
      if (constructor.parameterCount == 2) {
        return constructor.newInstance(componentManager!!, this) as StateStorage
      }
      else {
        return constructor.newInstance(collapsedPath, componentManager!!, this) as StateStorage
      }
    }

    val effectiveRoamingType = getEffectiveRoamingType(roamingType, collapsedPath)
    if (isUseVfsListener == ThreeState.UNSURE) {
      isUseVfsListener = ThreeState.fromBoolean(!compoundStreamProvider.isApplicable(collapsedPath, effectiveRoamingType))
    }

    val filePath = expandMacro(collapsedPath)
    @Suppress("DEPRECATION")
    if (stateSplitter != StateSplitter::class.java && stateSplitter != StateSplitterEx::class.java) {
      val storage = createDirectoryBasedStorage(filePath, collapsedPath, ReflectionUtil.newInstance(stateSplitter))
      if (storage is StorageVirtualFileTracker.TrackedStorage) {
        virtualFileTracker?.put(filePath.systemIndependentPath, storage)
      }
      return storage
    }

    val app = ApplicationManager.getApplication()
    if (app != null && !app.isHeadlessEnvironment && !filePath.fileName.toString().contains('.')) {
      throw IllegalArgumentException("Extension is missing for storage file: $filePath")
    }

    val storage = createFileBasedStorage(filePath, collapsedPath, effectiveRoamingType, if (exclusive) null else rootTagName)
    if (isUseVfsListener == ThreeState.YES && storage is StorageVirtualFileTracker.TrackedStorage) {
      virtualFileTracker?.put(filePath.systemIndependentPath, storage)
    }
    return storage
  }

  // open for upsource
  protected open fun createFileBasedStorage(path: Path,
                                            collapsedPath: String,
                                            roamingType: RoamingType,
                                            rootTagName: String?): StateStorage {
    val provider = if (roamingType == RoamingType.DISABLED) {
      // remove to ensure that repository doesn't store non-roamable files
      compoundStreamProvider.delete(collapsedPath, roamingType)
      null
    }
    else {
      compoundStreamProvider
    }
    return MyFileStorage(this, path, collapsedPath, rootTagName, roamingType, getMacroSubstitutor(collapsedPath), provider)
  }

  // open for upsource
  protected open fun createDirectoryBasedStorage(path: Path, collapsedPath: String, @Suppress("DEPRECATION") splitter: StateSplitter): StateStorage {
    return MyDirectoryStorage(this, path, splitter)
  }

  private class MyDirectoryStorage(override val storageManager: StateStorageManagerImpl, file: Path, @Suppress("DEPRECATION") splitter: StateSplitter) :
    DirectoryBasedStorage(file, splitter, storageManager.macroSubstitutor), StorageVirtualFileTracker.TrackedStorage

  protected open class MyFileStorage(override val storageManager: StateStorageManagerImpl,
                                     file: Path,
                                     fileSpec: String,
                                     rootElementName: String?,
                                     roamingType: RoamingType,
                                     pathMacroManager: PathMacroSubstitutor? = null,
                                     provider: StreamProvider? = null) : FileBasedStorage(file, fileSpec, rootElementName, pathMacroManager,
                                                                                          roamingType,
                                                                                          provider), StorageVirtualFileTracker.TrackedStorage {
    override val isUseXmlProlog: Boolean
      get() = rootElementName != null && storageManager.isUseXmlProlog && !isSpecialStorage(fileSpec)

    override val configuration: FileBasedStorageConfiguration
      get() = storageManager.getFileBasedStorageConfiguration(fileSpec)

    override fun beforeElementSaved(elements: MutableList<Element>, rootAttributes: MutableMap<String, String>) {
      if (rootElementName != null) {
        storageManager.beforeElementSaved(elements, rootAttributes)
      }
      super.beforeElementSaved(elements, rootAttributes)
    }

    override fun beforeElementLoaded(element: Element) {
      storageManager.beforeElementLoaded(element)
      super.beforeElementLoaded(element)
    }

    override fun providerDataStateChanged(writer: DataWriter?, type: DataStateChanged) {
      storageManager.providerDataStateChanged(this, writer, type)
      super.providerDataStateChanged(writer, type)
    }

    override fun getResolution(component: PersistentStateComponent<*>, operation: StateStorageOperation): Resolution {
      if (operation == StateStorageOperation.WRITE && component is ProjectModelElement && storageManager.isExternalSystemStorageEnabled && component.externalSource != null) {
        return Resolution.CLEAR
      }
      return Resolution.DO
    }
  }

  open val isExternalSystemStorageEnabled: Boolean
    get() = false

  // function must be pure and do not use anything outside of passed arguments
  protected open fun beforeElementSaved(elements: MutableList<Element>, rootAttributes: MutableMap<String, String>) {
  }

  protected open fun providerDataStateChanged(storage: FileBasedStorage, writer: DataWriter?, type: DataStateChanged) {
  }

  protected open fun beforeElementLoaded(element: Element) {
  }

  fun clearStorages() {
    storageLock.write {
      try {
        if (virtualFileTracker != null) {
          for (collapsedPath in storages.keys) {
            virtualFileTracker.remove(expandMacro(collapsedPath).systemIndependentPath)
          }
        }
      }
      finally {
        storages.clear()
      }
    }
  }

  protected open fun getMacroSubstitutor(fileSpec: String): PathMacroSubstitutor? = macroSubstitutor

  override fun expandMacro(collapsedPath: String): Path {
    for ((key, value) in macros) {
      if (key == collapsedPath) {
        return value
      }

      if (collapsedPath.length > (key.length + 2) && collapsedPath[key.length] == '/' && collapsedPath.startsWith(key)) {
        return value.resolve(collapsedPath.substring(key.length + 1))
      }
    }

    throw IllegalStateException("Cannot resolve $collapsedPath in $macros")
  }

  fun collapseMacros(path: String): String {
    var result = path
    for ((key, value) in macros) {
      result = result.replace(value.systemIndependentPath, key)
    }
    return normalizeFileSpec(result)
  }

  final override fun getOldStorage(component: Any, componentName: String, operation: StateStorageOperation): StateStorage? {
    val oldStorageSpec = getOldStorageSpec(component, componentName, operation) ?: return null
    return getOrCreateStorage(oldStorageSpec, RoamingType.DEFAULT)
  }

  protected open fun getOldStorageSpec(component: Any, componentName: String, operation: StateStorageOperation): String? = null
}

private fun String.startsWithMacro(macro: String): Boolean {
  val i = macro.length
  return getOrNull(i) == '/' && startsWith(macro)
}

fun removeMacroIfStartsWith(path: String, macro: String): String = if (path.startsWithMacro(macro)) path.substring(macro.length + 1) else path

@Suppress("DEPRECATION")
internal val Storage.path: String
  get() = if (value.isEmpty()) file else value

internal fun getEffectiveRoamingType(roamingType: RoamingType, collapsedPath: String): RoamingType {
  if (roamingType != RoamingType.DISABLED && (collapsedPath == StoragePathMacros.WORKSPACE_FILE || collapsedPath == StoragePathMacros.NON_ROAMABLE_FILE || isSpecialStorage(collapsedPath))) {
    return RoamingType.DISABLED
  }
  else {
    return roamingType
  }
}

data class Macro(val key: String, var value: Path)

class UnknownMacroException(message: String) : RuntimeException(message)