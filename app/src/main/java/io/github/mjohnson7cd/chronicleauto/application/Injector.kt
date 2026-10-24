package io.github.mjohnson7cd.chronicleauto.application

import io.github.mjohnson7cd.chronicleauto.injection.components.AppComponent

class Injector private constructor() {
    companion object {
        fun get(): AppComponent = ChronicleApplication.get().appComponent
    }
}
