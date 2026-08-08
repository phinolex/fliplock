// FlipLock - build racine.
// Les plugins sont declares ici sans etre appliques ; le module :app les applique.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
