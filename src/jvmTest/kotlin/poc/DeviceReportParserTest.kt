package poc
import kotlin.test.*

class DeviceReportParserTest {
    private val report = """
        wpd-device-count=1
        device-id=fake-selector
        usb-pnp-ancestor=1
        wpd-open=1
        usb-declared=1
        reported-protocol=MTP: 1.00
    """.trimIndent()
    @Test fun usbMtpIsOnlyTransportEvidence() {
        assertEquals(TransportState.USB_MTP_READY, DeviceReportParser.parse(report).state)
        assertEquals("fake-selector", DeviceReportParser.parse(report).localSelector)
    }
    @Test fun noDeviceAndAmbiguityNeverSelectAPhone() {
        assertEquals(TransportState.NONE, DeviceReportParser.parse("wpd-device-count=0").state)
        assertEquals(TransportState.AMBIGUOUS, DeviceReportParser.parse("wpd-device-count=2").state)
    }
    @Test fun deniedOrUnknownTransportIsUnavailable() {
        assertEquals(TransportState.UNAVAILABLE, DeviceReportParser.parse(report.replace("usb-declared=1", "usb-declared=0")).state)
        assertEquals(TransportState.UNAVAILABLE, DeviceReportParser.parse(report.replace("MTP: 1.00", "unknown")).state)
        assertEquals(TransportState.UNAVAILABLE, DeviceReportParser.parse(report.replace("MTP: 1.00", "PTP")).state)
    }
    @Test fun duplicateMissingOrInjectedFieldsFailClosed() {
        assertEquals(TransportState.ERROR, DeviceReportParser.parse(report + "\nwpd-open=1").state)
        assertEquals(TransportState.ERROR, DeviceReportParser.parse(report.replace("usb-declared=1\n", "")).state)
        assertEquals(TransportState.ERROR, DeviceReportParser.parse(report + "\ninjected=value").state)
    }
    @Test fun malformedCountAndAccessFailureFailClosed() {
        assertEquals(TransportState.ERROR, DeviceReportParser.parse("wpd-device-count=0\ndevice-id=fake").state)
        assertEquals(TransportState.UNAVAILABLE, DeviceReportParser.parse("wpd-device-count=1\ndevice-id=fake\nusb-pnp-ancestor=1\nwpd-open=0\nerror-hresult=80070005").state)
    }
}
