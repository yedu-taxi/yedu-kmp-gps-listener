package io.github.saggeldi.gps

/**
 * Represents the current status of location permission.
 */
enum class PermissionStatus {
    /** Permission has been granted by the user. */
    GRANTED,
    /** Permission has been explicitly denied by the user. */
    DENIED,
    /** Permission has not yet been requested (first launch). */
    NOT_DETERMINED,
    /** Permission is restricted by device policy (e.g. parental controls on iOS). */
    RESTRICTED
}
