package com.ivy.plugins

import com.ivy.plugins.api.IvyPlugin
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

@Module
@InstallIn(SingletonComponent::class)
interface PluginsModule {
    /**
     * Plugin registry. Each plugin module contributes to it with
     * `@Binds @IntoSet`. Declared here so the set exists even when empty.
     */
    @Multibinds
    fun plugins(): Set<IvyPlugin>
}
