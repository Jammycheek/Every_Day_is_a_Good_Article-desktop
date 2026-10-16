#include <windows.h>
#include <portabledeviceapi.h>
#include <portabledevice.h>
#include <wrl/client.h>
#include <cfgmgr32.h>
#include <initguid.h>
#include <devpkey.h>
#include <bcrypt.h>
#include <dpapi.h>
#include <iostream>
#include <vector>
#include <string>
#include <stdexcept>
#include <thread>
#include <chrono>
#include <algorithm>
#include <sstream>
#include <initializer_list>
#include <cstdio>
using Microsoft::WRL::ComPtr;
constexpr size_t MAX_BYTES = 65536;
// Opt-in local diagnostics: fixed stage names only; never selectors, keys, or file contents.
bool probeTracing = false;
void probeStage(const char* stage) {
    if (!probeTracing) return;
    std::cerr << "probe-stage=" << stage << "\n" << std::flush;
    WCHAR path[32768] = {};
    DWORD size = GetEnvironmentVariableW(L"EVERYARTICLE_PROBE_TRACE_FILE", path, 32768);
    if (size && size < 32768) {
        FILE* file = nullptr;
        if (_wfopen_s(&file, path, L"ab") == 0 && file) {
            fprintf(file, "pid=%lu stage=%s\n", GetCurrentProcessId(), stage);
            fclose(file);
        }
    }
}
// Wide console output, UTF-8 when redirected. Do not depend on the user's code page.
struct LocalOutput {
    std::wostringstream buffer;
    template<class T> LocalOutput& operator<<(T value) { buffer << value; return *this; }
    ~LocalOutput() {
        auto wide = buffer.str();
        DWORD mode = 0;
        HANDLE handle = GetStdHandle(STD_OUTPUT_HANDLE);
        if (GetConsoleMode(handle, &mode)) {
            DWORD written = 0;
            WriteConsoleW(handle, wide.data(), static_cast<DWORD>(wide.size()), &written, nullptr);
        } else {
            int count = WideCharToMultiByte(CP_UTF8, 0, wide.data(), static_cast<int>(wide.size()), nullptr, 0, nullptr, nullptr);
            std::string utf8(count, '\0');
            WideCharToMultiByte(CP_UTF8, 0, wide.data(), static_cast<int>(wide.size()), utf8.data(), count, nullptr, nullptr);
            std::cout << utf8 << std::flush;
        }
    }
};
void checked(HRESULT hr) { if (FAILED(hr)) throw hr; }
template<class T> ComPtr<T> make(REFCLSID clsid) {
    ComPtr<T> value;
    checked(CoCreateInstance(clsid, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(value.GetAddressOf())));
    return value;
}
struct Text {
    PWSTR value = nullptr;
    ~Text() { CoTaskMemFree(value); }
    std::wstring str() const { return value ? value : L""; }
};
std::wstring text(IPortableDeviceValues* values, REFPROPERTYKEY key) {
    Text result; checked(values->GetStringValue(key, &result.value)); return result.str();
}
ComPtr<IPortableDeviceValues> properties(IPortableDeviceContent* content, PCWSTR id,
    std::initializer_list<PROPERTYKEY> requested) {
    if (requested.size() == 0) throw std::runtime_error("Explicit property keys required");
    auto keys = make<IPortableDeviceKeyCollection>(CLSID_PortableDeviceKeyCollection);
    for (const auto& key : requested) checked(keys->Add(key));
    ComPtr<IPortableDeviceProperties> props;
    probeStage("get-properties-interface");
    checked(content->Properties(&props));
    ComPtr<IPortableDeviceValues> values;
    // NULL requests every supported attribute, which can block even the connection probe.
    probeStage("get-explicit-values");
    checked(props->GetValues(id, keys.Get(), &values));
    probeStage("values-read");
    return values;
}
bool usbAncestor(const std::wstring& id) {
    DEVINST node;
    if (CM_Locate_DevNodeW(&node, const_cast<PWSTR>(id.c_str()), CM_LOCATE_DEVNODE_NORMAL) != CR_SUCCESS) {
        // WPD GetDevices returns interface symbolic links on many phones, not devnode instance IDs.
        WCHAR instance[MAX_DEVICE_ID_LEN] = {};
        DEVPROPTYPE type = 0;
        ULONG size = sizeof(instance);
        CONFIGRET result = CM_Get_Device_Interface_PropertyW(id.c_str(), &DEVPKEY_Device_InstanceId,
            &type, reinterpret_cast<PBYTE>(instance), &size, 0);
        if (result != CR_SUCCESS || type != DEVPROP_TYPE_STRING || size < sizeof(WCHAR)
            || size > sizeof(instance) || size % sizeof(WCHAR) != 0
            || instance[size / sizeof(WCHAR) - 1] != L'\0' || instance[0] == L'\0') return false;
        if (CM_Locate_DevNodeW(&node, instance, CM_LOCATE_DEVNODE_NORMAL) != CR_SUCCESS) return false;
    }
    for (int depth = 0; depth < 16; ++depth) {
        WCHAR buffer[MAX_DEVICE_ID_LEN] = {};
        if (CM_Get_Device_IDW(node, buffer, MAX_DEVICE_ID_LEN, 0) != CR_SUCCESS) return false;
        if (_wcsnicmp(buffer, L"USB\\", 4) == 0) return true;
        DEVINST parent;
        if (CM_Get_Parent(&parent, node, 0) != CR_SUCCESS) return false;
        node = parent;
    }
    return false;
}
struct Device {
    ComPtr<IPortableDevice> device;
    ComPtr<IPortableDeviceContent> content;
    explicit Device(PCWSTR id, bool writable) {
        probeStage("create-device");
        device = make<IPortableDevice>(CLSID_PortableDeviceFTM);
        probeStage("create-client-info");
        auto client = make<IPortableDeviceValues>(CLSID_PortableDeviceValues);
        checked(client->SetStringValue(WPD_CLIENT_NAME, L"EveryArticle Synthetic Probe"));
        checked(client->SetUnsignedIntegerValue(WPD_CLIENT_MAJOR_VERSION, 0));
        checked(client->SetUnsignedIntegerValue(WPD_CLIENT_MINOR_VERSION, 1));
        checked(client->SetUnsignedIntegerValue(WPD_CLIENT_REVISION, 0));
        checked(client->SetUnsignedIntegerValue(WPD_CLIENT_DESIRED_ACCESS, writable ? GENERIC_READ | GENERIC_WRITE : GENERIC_READ));
        probeStage("open-device");
        checked(device->Open(id, client.Get()));
        probeStage("open-content");
        checked(device->Content(&content));
        probeStage("content-opened");
    }
    ~Device() {
        // Release content interfaces before the device. Calling Close with live child interfaces can
        // leave the next short-lived client waiting on the preceding transfer's teardown.
        probeStage("release-content"); content.Reset();
        probeStage("release-device"); device.Reset();
        probeStage("device-released");
    }
    Device(const Device&) = delete;
    Device& operator=(const Device&) = delete;
};
bool declaredUsb(IPortableDeviceContent* content) {
    auto values = properties(content, WPD_DEVICE_OBJECT_ID, {WPD_DEVICE_TRANSPORT});
    ULONG transport = 0;
    return SUCCEEDED(values->GetUnsignedIntegerValue(WPD_DEVICE_TRANSPORT, &transport)) && transport == WPD_DEVICE_TRANSPORT_USB;
}
void devices() {
    probeStage("create-device-manager");
    auto manager = make<IPortableDeviceManager>(CLSID_PortableDeviceManager);
    probeStage("refresh-device-list");
    checked(manager->RefreshDeviceList());
    probeStage("enumerate-devices");
    DWORD count = 0;
    checked(manager->GetDevices(nullptr, &count));
    if (count > 256) throw std::runtime_error("Too many devices");
    struct Ids {
        std::vector<PWSTR> ids;
        ~Ids() { for (auto id : ids) CoTaskMemFree(id); }
    } values;
    values.ids.resize(count);
    if (count) checked(manager->GetDevices(values.ids.data(), &count));
    LocalOutput() << L"wpd-device-count=" << count << L"\n";
    for (DWORD i = 0; i < count; ++i) {
        std::wstring id = values.ids.at(i);
        // IDs are local selectors only. Do not upload this output to public issue/CI logs.
        LocalOutput() << L"device-id=" << id << L"\nusb-pnp-ancestor=" << usbAncestor(id) << L"\n";
        try {
            Device d(id.c_str(), false);
            probeStage("read-device-properties");
            auto props = properties(d.content.Get(), WPD_DEVICE_OBJECT_ID, {WPD_DEVICE_TRANSPORT, WPD_DEVICE_PROTOCOL});
            ULONG transport = 0;
            bool usb = SUCCEEDED(props->GetUnsignedIntegerValue(WPD_DEVICE_TRANSPORT, &transport)) && transport == WPD_DEVICE_TRANSPORT_USB;
            LocalOutput() << L"wpd-open=1\nusb-declared=" << usb << L"\n";
            Text protocol;
            if (SUCCEEDED(props->GetStringValue(WPD_DEVICE_PROTOCOL, &protocol.value)))
                LocalOutput() << L"reported-protocol=" << protocol.str() << L"\n";
            else LocalOutput() << L"reported-protocol=unknown\n";
        } catch (HRESULT hr) {
            LocalOutput() << L"wpd-open=0\nerror-hresult=" << std::hex << static_cast<unsigned long>(hr) << std::dec << L"\n";
        }
    }
}
void children(IPortableDeviceContent* content, PCWSTR parent) {
    ComPtr<IEnumPortableDeviceObjectIDs> objects;
    checked(content->EnumObjects(0, parent, nullptr, &objects));
    for (size_t i = 0; i < 256; ++i) {
        Text id; ULONG fetched = 0;
        checked(objects->Next(1, &id.value, &fetched));
        if (!fetched) return;
        auto props = properties(content, id.value, {WPD_OBJECT_CONTENT_TYPE, WPD_OBJECT_NAME});
        GUID type = {};
        checked(props->GetGuidValue(WPD_OBJECT_CONTENT_TYPE, &type));
        LocalOutput() << L"object-id=" << id.str() << L"\nname=" << text(props.Get(), WPD_OBJECT_NAME)
                   << L"\nfolder=" << IsEqualGUID(type, WPD_CONTENT_TYPE_FOLDER) << L"\n";
    }
    LocalOutput() << L"listing-limit-reached=1\n";
}
std::vector<unsigned char> readBounded(IStream* stream) {
    std::vector<unsigned char> bytes;
    unsigned char chunk[4096];
    for (;;) {
        ULONG read = 0;
        HRESULT hr = stream->Read(chunk, sizeof(chunk), &read);
        checked(hr);
        if (read > sizeof(chunk) || bytes.size() + read > MAX_BYTES) throw std::runtime_error("Read exceeds limit");
        bytes.insert(bytes.end(), chunk, chunk + read);
        if (read == 0) return bytes;
    }
}
void writeAll(IStream* stream, const std::vector<unsigned char>& bytes, bool slow = false) {
    if (bytes.size() > MAX_BYTES) throw std::runtime_error("Write exceeds limit");
    size_t offset = 0;
    while (offset < bytes.size()) {
        ULONG written = 0;
        ULONG requested = static_cast<ULONG>(slow ? (std::min)(size_t(1024), bytes.size() - offset) : bytes.size() - offset);
        checked(stream->Write(bytes.data() + offset, requested, &written));
        if (!written || written > requested) throw std::runtime_error("Incomplete write");
        offset += written;
        if (slow) {
            LocalOutput() << L"transfer-written=" << offset << L"\n";
            std::this_thread::sleep_for(std::chrono::seconds(1));
        }
    }
}
void verify(const std::vector<unsigned char>& expected, const std::vector<unsigned char>& actual) {
    if (expected != actual) throw std::runtime_error("Round trip mismatch");
}
std::wstring sha256(const std::vector<unsigned char>& bytes) {
    unsigned char hash[32];
    NTSTATUS status = BCryptHash(BCRYPT_SHA256_ALG_HANDLE, nullptr, 0,
        const_cast<PUCHAR>(bytes.data()), static_cast<ULONG>(bytes.size()), hash, sizeof(hash));
    if (status < 0) throw std::runtime_error("Hash failed");
    const wchar_t* digits = L"0123456789abcdef";
    std::wstring result;
    for (auto b : hash) { result += digits[b >> 4]; result += digits[b & 15]; }
    return result;
}
void roundtrip(PCWSTR id, PCWSTR parent, bool empty, bool slow = false) {
    Device d(id, true);
    if (!declaredUsb(d.content.Get()) || !usbAncestor(id)) throw std::runtime_error("USB evidence is incomplete");
    auto folder = properties(d.content.Get(), parent, {WPD_OBJECT_CONTENT_TYPE, WPD_OBJECT_NAME});
    GUID type = {};
    checked(folder->GetGuidValue(WPD_OBJECT_CONTENT_TYPE, &type));
    if (!IsEqualGUID(type, WPD_CONTENT_TYPE_FOLDER) || text(folder.Get(), WPD_OBJECT_NAME) != L"EveryArticle-PoC")
        throw std::runtime_error("Select the dedicated EveryArticle-PoC folder");
    GUID nonce;
    checked(CoCreateGuid(&nonce));
    WCHAR name[64];
    if (!StringFromGUID2(nonce, name, 64)) throw std::runtime_error("Nonce failed");
    std::wstring filename = L"ea-poc-" + std::wstring(name) + L"-検証.bin";
    std::vector<unsigned char> bytes(empty ? 0 : MAX_BYTES);
    if (!empty && BCryptGenRandom(nullptr, bytes.data(), static_cast<ULONG>(bytes.size()), BCRYPT_USE_SYSTEM_PREFERRED_RNG) < 0)
        throw std::runtime_error("Random generation failed");
    auto props = make<IPortableDeviceValues>(CLSID_PortableDeviceValues);
    checked(props->SetStringValue(WPD_OBJECT_PARENT_ID, parent));
    checked(props->SetStringValue(WPD_OBJECT_NAME, filename.c_str()));
    checked(props->SetStringValue(WPD_OBJECT_ORIGINAL_FILE_NAME, filename.c_str()));
    checked(props->SetUnsignedLargeIntegerValue(WPD_OBJECT_SIZE, bytes.size()));
    checked(props->SetGuidValue(WPD_OBJECT_CONTENT_TYPE, WPD_CONTENT_TYPE_GENERIC_FILE));
    checked(props->SetGuidValue(WPD_OBJECT_FORMAT, WPD_OBJECT_FORMAT_UNSPECIFIED));
    ComPtr<IStream> output;
    DWORD optimal = 0;
    checked(d.content->CreateObjectWithPropertiesAndData(props.Get(), &output, &optimal, nullptr));
    try {
        writeAll(output.Get(), bytes, slow);
        checked(output->Commit(STGC_DEFAULT));
    } catch (...) {
        output->Revert(); // best effort only; a timeout can leave a partial synthetic file
        throw;
    }
    ComPtr<IPortableDeviceDataStream> data;
    checked(output.As(&data));
    Text created;
    checked(data->GetObjectID(&created.value));
    LocalOutput() << L"created-object-id=" << created.str() << L"\ncreated-name=" << filename << L"\n";
    data.Reset(); output.Reset();
    ComPtr<IPortableDeviceResources> resources;
    checked(d.content->Transfer(&resources));
    ComPtr<IStream> input;
    checked(resources->GetStream(created.value, WPD_RESOURCE_DEFAULT, STGM_READ, &optimal, &input));
    auto returned = readBounded(input.Get());
    verify(bytes, returned);
    LocalOutput() << L"roundtrip-verified=1\nbytes=" << bytes.size() << L"\nsha256=" << sha256(returned) << L"\n";
    // No delete API. The user removes this uniquely named synthetic file manually.
}
// ---- Sync mailbox (protocol v1). Only EveryArticleSync/to-pc|to-phone/<13 digits>-<16 hex>.eam is touched. ----
constexpr size_t MAILBOX_MAX_BYTES = 8 * 1024 * 1024;
const wchar_t* MAILBOX_ROOT = L"EveryArticleSync";
bool mailboxName(const std::wstring& name) {
    if (name.size() != 13 + 1 + 16 + 4 || name.substr(30) != L".eam" || name[13] != L'-') return false;
    for (size_t i = 0; i < 13; ++i) if (name[i] < L'0' || name[i] > L'9') return false;
    for (size_t i = 14; i < 30; ++i) if (!((name[i] >= L'0' && name[i] <= L'9') || (name[i] >= L'a' && name[i] <= L'f'))) return false;
    return true;
}
bool mailboxFolder(const std::wstring& name) { return name == L"to-pc" || name == L"to-phone"; }
std::vector<std::wstring> childIds(IPortableDeviceContent* content, PCWSTR parent, size_t limit) {
    ComPtr<IEnumPortableDeviceObjectIDs> objects;
    probeStage("enumerate-children");
    checked(content->EnumObjects(0, parent, nullptr, &objects));
    std::vector<std::wstring> ids;
    for (;;) {
        Text id; ULONG fetched = 0;
        probeStage("read-next-child");
        checked(objects->Next(1, &id.value, &fetched));
        if (!fetched) return ids;
        if (ids.size() >= limit) throw std::runtime_error("Mailbox listing limit reached");
        ids.push_back(id.str());
    }
}
bool isFolder(IPortableDeviceValues* props) {
    GUID type = {};
    return SUCCEEDED(props->GetGuidValue(WPD_OBJECT_CONTENT_TYPE, &type)) &&
        (IsEqualGUID(type, WPD_CONTENT_TYPE_FOLDER) || IsEqualGUID(type, WPD_CONTENT_TYPE_FUNCTIONAL_OBJECT));
}
// Resolve EveryArticleSync/<box> below exactly one storage. Ambiguity is an error, never a guess.
std::wstring mailboxFolderId(IPortableDeviceContent* content, const std::wstring& box) {
    if (!mailboxFolder(box)) throw std::runtime_error("Unknown mailbox folder");
    std::vector<std::wstring> matches;
    for (auto& storage : childIds(content, WPD_DEVICE_OBJECT_ID, 64)) {
        for (auto& rootChild : childIds(content, storage.c_str(), 4096)) {
            auto rootProps = properties(content, rootChild.c_str(), {WPD_OBJECT_CONTENT_TYPE, WPD_OBJECT_NAME});
            if (!isFolder(rootProps.Get()) || text(rootProps.Get(), WPD_OBJECT_NAME) != MAILBOX_ROOT) continue;
            for (auto& boxChild : childIds(content, rootChild.c_str(), 64)) {
                auto boxProps = properties(content, boxChild.c_str(), {WPD_OBJECT_CONTENT_TYPE, WPD_OBJECT_NAME});
                if (isFolder(boxProps.Get()) && text(boxProps.Get(), WPD_OBJECT_NAME) == box) matches.push_back(boxChild);
            }
        }
    }
    if (matches.size() != 1) throw std::runtime_error(matches.empty() ? "Mailbox folder not found" : "Mailbox folder is ambiguous");
    return matches.front();
}
// An object may be read/deleted only if it is a mailbox file directly inside a mailbox folder below EveryArticleSync.
void requireMailboxObject(IPortableDeviceContent* content, PCWSTR id) {
    auto props = properties(content, id, {WPD_OBJECT_CONTENT_TYPE, WPD_OBJECT_NAME, WPD_OBJECT_PARENT_ID});
    if (isFolder(props.Get()) || !mailboxName(text(props.Get(), WPD_OBJECT_NAME))) throw std::runtime_error("Not a mailbox file");
    auto parent = properties(content, text(props.Get(), WPD_OBJECT_PARENT_ID).c_str(), {WPD_OBJECT_NAME, WPD_OBJECT_PARENT_ID});
    if (!mailboxFolder(text(parent.Get(), WPD_OBJECT_NAME))) throw std::runtime_error("Not in a mailbox folder");
    auto grandparent = properties(content, text(parent.Get(), WPD_OBJECT_PARENT_ID).c_str(), {WPD_OBJECT_NAME});
    if (text(grandparent.Get(), WPD_OBJECT_NAME) != MAILBOX_ROOT) throw std::runtime_error("Not below EveryArticleSync");
}
void requireUsbMailbox(PCWSTR id, Device& d) {
    if (!declaredUsb(d.content.Get()) || !usbAncestor(id)) throw std::runtime_error("USB evidence is incomplete");
}
void writeStdout(const unsigned char* data, size_t size) {
    size_t offset = 0;
    while (offset < size) {
        DWORD written = 0;
        DWORD chunk = static_cast<DWORD>((std::min)(size - offset, size_t(1) << 20));
        if (!WriteFile(GetStdHandle(STD_OUTPUT_HANDLE), data + offset, chunk, &written, nullptr) || !written)
            throw std::runtime_error("Output incomplete");
        offset += written;
    }
}
void mailboxList(PCWSTR deviceId, const std::wstring& box) {
    Device d(deviceId, false);
    requireUsbMailbox(deviceId, d);
    auto folder = mailboxFolderId(d.content.Get(), box);
    for (auto& id : childIds(d.content.Get(), folder.c_str(), 512)) {
        auto props = properties(d.content.Get(), id.c_str(), {WPD_OBJECT_CONTENT_TYPE, WPD_OBJECT_NAME, WPD_OBJECT_SIZE});
        if (isFolder(props.Get())) continue;
        auto name = text(props.Get(), WPD_OBJECT_NAME);
        if (!mailboxName(name)) continue;
        ULONGLONG size = 0;
        if (FAILED(props->GetUnsignedLargeIntegerValue(WPD_OBJECT_SIZE, &size))) continue;
        LocalOutput() << L"object-id=" << id << L"\nname=" << name << L"\nsize=" << size << L"\n";
    }
}
void mailboxRead(PCWSTR deviceId, PCWSTR objectId) {
    Device d(deviceId, false);
    requireUsbMailbox(deviceId, d);
    requireMailboxObject(d.content.Get(), objectId);
    ComPtr<IPortableDeviceResources> resources;
    checked(d.content->Transfer(&resources));
    ComPtr<IStream> input;
    DWORD optimal = 0;
    checked(resources->GetStream(objectId, WPD_RESOURCE_DEFAULT, STGM_READ, &optimal, &input));
    std::vector<unsigned char> bytes;
    unsigned char chunk[65536];
    for (;;) {
        ULONG read = 0;
        checked(input->Read(chunk, sizeof(chunk), &read));
        if (!read) break;
        if (bytes.size() + read > MAILBOX_MAX_BYTES) throw std::runtime_error("Mailbox file too large");
        bytes.insert(bytes.end(), chunk, chunk + read);
    }
    writeStdout(bytes.data(), bytes.size());
}
void mailboxWrite(PCWSTR deviceId, const std::wstring& box, const std::wstring& name) {
    if (!mailboxName(name)) throw std::runtime_error("Invalid mailbox file name");
    std::vector<unsigned char> bytes;
    unsigned char chunk[65536];
    probeStage("read-mailbox-input");
    for (;;) {
        DWORD count = 0;
        if (!ReadFile(GetStdHandle(STD_INPUT_HANDLE), chunk, sizeof(chunk), &count, nullptr)) {
            if (GetLastError() == ERROR_BROKEN_PIPE) break;
            checked(HRESULT_FROM_WIN32(GetLastError()));
        }
        if (!count) break;
        if (bytes.size() + count > MAILBOX_MAX_BYTES) throw std::runtime_error("Mailbox input too large");
        bytes.insert(bytes.end(), chunk, chunk + count);
    }
    if (bytes.empty()) throw std::runtime_error("Mailbox input missing");
    probeStage("mailbox-input-complete");
    Device d(deviceId, true);
    requireUsbMailbox(deviceId, d);
    probeStage("find-mailbox-folder");
    auto folder = mailboxFolderId(d.content.Get(), box);
    auto props = make<IPortableDeviceValues>(CLSID_PortableDeviceValues);
    checked(props->SetStringValue(WPD_OBJECT_PARENT_ID, folder.c_str()));
    checked(props->SetStringValue(WPD_OBJECT_NAME, name.c_str()));
    checked(props->SetStringValue(WPD_OBJECT_ORIGINAL_FILE_NAME, name.c_str()));
    checked(props->SetUnsignedLargeIntegerValue(WPD_OBJECT_SIZE, bytes.size()));
    checked(props->SetGuidValue(WPD_OBJECT_CONTENT_TYPE, WPD_CONTENT_TYPE_GENERIC_FILE));
    checked(props->SetGuidValue(WPD_OBJECT_FORMAT, WPD_OBJECT_FORMAT_UNSPECIFIED));
    ComPtr<IStream> output;
    DWORD optimal = 0;
    probeStage("create-message-object");
    checked(d.content->CreateObjectWithPropertiesAndData(props.Get(), &output, &optimal, nullptr));
    try {
        size_t offset = 0;
        while (offset < bytes.size()) {
            ULONG written = 0;
            ULONG requested = static_cast<ULONG>((std::min)(bytes.size() - offset, size_t(65536)));
            probeStage("write-message-stream");
            checked(output->Write(bytes.data() + offset, requested, &written));
            if (!written || written > requested) throw std::runtime_error("Incomplete write");
            offset += written;
        }
        probeStage("commit-message-stream");
        checked(output->Commit(STGC_DEFAULT));
    } catch (...) {
        output->Revert(); // best effort; the phone ignores invalid envelopes for 30 seconds
        throw;
    }
    LocalOutput() << L"mailbox-written=1\n";
}
void mailboxDelete(PCWSTR deviceId, PCWSTR objectId) {
    Device d(deviceId, true);
    requireUsbMailbox(deviceId, d);
    requireMailboxObject(d.content.Get(), objectId);
    auto ids = make<IPortableDevicePropVariantCollection>(CLSID_PortableDevicePropVariantCollection);
    PROPVARIANT value;
    PropVariantInit(&value);
    value.vt = VT_LPWSTR;
    value.pwszVal = const_cast<LPWSTR>(objectId);
    checked(ids->Add(&value)); // Add copies the value; do not clear the borrowed pointer
    checked(d.content->Delete(PORTABLE_DEVICE_DELETE_NO_RECURSION, ids.Get(), nullptr));
    LocalOutput() << L"mailbox-deleted=1\n";
}
// Binary unprotect status, separate key-state hints from data rejection.
int rejectionStatus(DWORD error) {
    if (error == ERROR_INVALID_DATA || error == ERROR_INVALID_PARAMETER || error == NTE_BAD_DATA) return 3;
    if (error == NTE_BAD_KEY_STATE || error == NTE_BAD_KEYSET) return 4;
    return 1; // resource/unknown failures are operational, not evidence of corrupt data
}
void selfTest() {
    if (rejectionStatus(ERROR_INVALID_DATA) != 3 || rejectionStatus(static_cast<DWORD>(NTE_BAD_DATA)) != 3 ||
        rejectionStatus(static_cast<DWORD>(NTE_BAD_KEY_STATE)) != 4 || rejectionStatus(static_cast<DWORD>(NTE_BAD_KEYSET)) != 4 ||
        rejectionStatus(ERROR_INVALID_PARAMETER) != 3 || rejectionStatus(ERROR_ACCESS_DENIED) != 1)
        throw std::runtime_error("Protection status classification failed");
    if (usbAncestor(L"EveryArticle-invalid-device-selector")) throw std::runtime_error("Invalid selector accepted");
    if (!mailboxName(L"1760000000000-0123456789abcdef.eam") || mailboxName(L"1760000000000-0123456789ABCDEF.eam") ||
        mailboxName(L"../1760000000000-0123456789abcdef.eam") || mailboxName(L"1760000000000-0123456789abcdef.eam.part") ||
        !mailboxFolder(L"to-pc") || mailboxFolder(L"DCIM"))
        throw std::runtime_error("Mailbox name policy failed");
    for (size_t size : {size_t(0), size_t(1), MAX_BYTES}) {
        std::vector<unsigned char> expected(size, 0x5a);
        ComPtr<IStream> memory;
        checked(CreateStreamOnHGlobal(nullptr, TRUE, &memory));
        writeAll(memory.Get(), expected, size == 1);
        LARGE_INTEGER start = {};
        checked(memory->Seek(start, STREAM_SEEK_SET, nullptr));
        verify(expected, readBounded(memory.Get()));
    }
    bool rejected = false;
    try { verify({1}, {}); } catch (const std::runtime_error&) { rejected = true; }
    if (!rejected) throw std::runtime_error("Truncation accepted");
    rejected = false;
    try { verify({1}, {2}); } catch (const std::runtime_error&) { rejected = true; }
    if (!rejected) throw std::runtime_error("Corruption accepted");
    ComPtr<IStream> oversized;
    checked(CreateStreamOnHGlobal(nullptr, TRUE, &oversized));
    std::vector<unsigned char> large(MAX_BYTES + 1, 1);
    ULONG written;
    checked(oversized->Write(large.data(), static_cast<ULONG>(large.size()), &written));
    LARGE_INTEGER start = {}; checked(oversized->Seek(start, STREAM_SEEK_SET, nullptr));
    rejected = false;
    try { readBounded(oversized.Get()); } catch (const std::runtime_error&) { rejected = true; }
    if (!rejected) throw std::runtime_error("Oversized read accepted");
    if (sha256({}) != L"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855") throw std::runtime_error("SHA-256 failed");
    LocalOutput() << L"self-test=passed\n";
}
// Binary pipe only: recovery payloads never appear in console arguments or diagnostic logs.
struct DecryptionRejected { int status; };
void protectPipe(bool encrypt) {
    std::vector<BYTE> input;
    BYTE chunk[4096];
    DWORD count = 0;
    for (;;) {
        if (!ReadFile(GetStdHandle(STD_INPUT_HANDLE), chunk, sizeof(chunk), &count, nullptr)) {
            if (GetLastError() == ERROR_BROKEN_PIPE) break;
            checked(HRESULT_FROM_WIN32(GetLastError()));
        }
        if (!count) break;
        if (input.size() + count > 131072) throw std::runtime_error("Recovery input too large");
        input.insert(input.end(), chunk, chunk + count);
    }
    if (input.empty()) throw std::runtime_error("Recovery input missing");
    DATA_BLOB source = {static_cast<DWORD>(input.size()), input.data()};
    DATA_BLOB output = {};
    BOOL success = encrypt
        ? CryptProtectData(&source, L"EveryArticle PoC delta", nullptr, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &output)
        : CryptUnprotectData(&source, nullptr, nullptr, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &output);
    DWORD error = GetLastError();
    SecureZeroMemory(input.data(), input.size());
    if (!success) {
        // Dedicated status only for known data/key rejection, never helper/pipe/resource failure.
        if (!encrypt && rejectionStatus(error) != 1)
            throw DecryptionRejected{rejectionStatus(error)};
        checked(HRESULT_FROM_WIN32(error));
    }
    struct ProtectedBuffer {
        DATA_BLOB& blob;
        ~ProtectedBuffer() { SecureZeroMemory(blob.pbData, blob.cbData); LocalFree(blob.pbData); }
    } cleanup{output};
    if (!output.cbData || output.cbData > 131072) throw std::runtime_error("Recovery output invalid");
    DWORD offset = 0;
    while (offset < output.cbData) {
        DWORD written = 0;
        if (!WriteFile(GetStdHandle(STD_OUTPUT_HANDLE), output.pbData + offset, output.cbData - offset, &written, nullptr))
            checked(HRESULT_FROM_WIN32(GetLastError()));
        if (!written) throw std::runtime_error("Recovery output incomplete");
        offset += written;
    }
}
int wmain(int argc, wchar_t** argv) {
    if (argc < 2) return 2;
    // Hard process deadline also covers a blocked COM call. It cannot roll back remote writes.
    const bool slowMode = std::wstring(argv[1]) == L"roundtrip-slow";
    std::thread([slowMode] {
        std::this_thread::sleep_for(std::chrono::seconds(slowMode ? 90 : 30));
        TerminateProcess(GetCurrentProcess(), 124);
    }).detach();
    HRESULT init = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(init)) return 1;
    int result = 0;
    try {
        std::wstring command = argv[1];
        WCHAR traceFlag[2] = {};
        probeTracing = (command == L"devices" || command.rfind(L"mailbox-", 0) == 0) &&
            GetEnvironmentVariableW(L"EVERYARTICLE_PROBE_TRACE", traceFlag, 2) == 1 && traceFlag[0] == L'1';
        if ((command == L"protect" || command == L"unprotect") && argc == 2) protectPipe(command == L"protect");
        else if (command == L"self-test" && argc == 2) selfTest();
        else if (command == L"devices" && argc == 2) devices();
        else if (command == L"children" && argc == 4) {
            Device d(argv[2], false); children(d.content.Get(), argv[3]);
        } else if ((command == L"roundtrip" || command == L"roundtrip-slow") && argc == 6 && std::wstring(argv[4]) == L"--synthetic-write"
                   && (std::wstring(argv[5]) == L"empty" || std::wstring(argv[5]) == L"64k")) {
            if (slowMode && std::wstring(argv[5]) != L"64k") throw std::runtime_error("Slow mode requires 64k");
            roundtrip(argv[2], argv[3], std::wstring(argv[5]) == L"empty", slowMode);
        } else if (command == L"mailbox-list" && argc == 4) mailboxList(argv[2], argv[3]);
        else if (command == L"mailbox-read" && argc == 4) mailboxRead(argv[2], argv[3]);
        else if (command == L"mailbox-write" && argc == 5) mailboxWrite(argv[2], argv[3], argv[4]);
        else if (command == L"mailbox-delete" && argc == 4) mailboxDelete(argv[2], argv[3]);
        else { std::wcerr << L"Invalid command. See docs/WINDOWS-PROBE.md\n"; result = 2; }
    } catch (const DecryptionRejected& rejection) {
        result = rejection.status; // Completed input and no output: 3=data rejection, 4=key-state hint.
    } catch (HRESULT hr) {
        std::wcerr << L"error-hresult=" << std::hex << static_cast<unsigned long>(hr) << L"\n"; result = 1;
    } catch (const std::exception& e) {
        std::cerr << e.what() << "\n"; result = 1;
    }
    CoUninitialize();
    return result;
}
