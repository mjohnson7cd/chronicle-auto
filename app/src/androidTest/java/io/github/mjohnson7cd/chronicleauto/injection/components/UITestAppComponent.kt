package io.github.mjohnson7cd.chronicleauto.injection.components

import dagger.Component
import io.github.mjohnson7cd.chronicleauto.injection.modules.UITestAppModule
import javax.inject.Singleton

@Component(modules = [UITestAppModule::class])
@Singleton
interface UITestAppComponent : AppComponent {
    // Test injection methods can be added here as needed
}
