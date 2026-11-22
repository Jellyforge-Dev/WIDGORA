package io.github.jellyforgedev.widgora.widget

/**
 * The widget listing the certificates the source reports as running out, or as ones it will not
 * vouch for.
 *
 * A class of its own with no code in it, for the two reasons a widget kind needs one: a host binds
 * a placement to a provider class name, and the metadata that says what sizes this kind is placed
 * at hangs off this class — see `res/xml/certificates_widget_info.xml`. The behaviour is in
 * [WidgoraWidgetProvider], and what this kind *shows* is decided in [WidgetModelSelection] from the
 * placement's own stored settings.
 */
class CertificatesWidgetProvider : WidgoraWidgetProvider()
