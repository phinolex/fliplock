# FlipLock n'utilise aucune réflexion, aucune sérialisation dynamique et aucune
# bibliothèque tierce nécessitant des règles particulières.
#
# Les classes référencées depuis AndroidManifest.xml (MainActivity, WakeUpActivity,
# FlipLockApp, FlipLockAccessibilityService, FlipLockForegroundService) sont
# conservées automatiquement par les règles générées par AGP.
#
# On conserve les noms de fichiers/lignes pour que les rares traces d'exception
# restent lisibles dans un rapport de bug.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
