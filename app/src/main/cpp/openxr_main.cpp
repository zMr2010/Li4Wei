#include <android/log.h>
#include <android/bitmap.h>
#include <android_native_app_glue.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <jni.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>
#include "xr_input.hpp"

#define INFO(...) __android_log_print(ANDROID_LOG_INFO, "FourthwardXR", __VA_ARGS__)
#define ERROR(...) __android_log_print(ANDROID_LOG_ERROR, "FourthwardXR", __VA_ARGS__)

namespace {
void check(XrResult r, const char* operation) {
    if (XR_FAILED(r)) throw std::runtime_error(std::string(operation) + " failed: " + std::to_string(r));
}
#define XR_CHECK(call) check((call), #call)
using Matrix = std::array<float, 16>;
constexpr Matrix identity{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
constexpr jsize uiQuadStride = 21;
Matrix multiply(const Matrix& a, const Matrix& b) {
    Matrix out{};
    for (int col = 0; col < 4; ++col) for (int row = 0; row < 4; ++row)
        for (int k = 0; k < 4; ++k) out[col * 4 + row] += a[k * 4 + row] * b[col * 4 + k];
    return out;
}
Matrix poseMatrix(const XrPosef& pose) {
    const auto q = pose.orientation;
    const float x = q.x, y = q.y, z = q.z, w = q.w;
    return {1-2*(y*y+z*z), 2*(x*y+z*w), 2*(x*z-y*w), 0,
            2*(x*y-z*w), 1-2*(x*x+z*z), 2*(y*z+x*w), 0,
            2*(x*z+y*w), 2*(y*z-x*w), 1-2*(x*x+y*y), 0,
            pose.position.x, pose.position.y, pose.position.z, 1};
}
Matrix inversePose(const XrPosef& pose) {
    const Matrix world = poseMatrix(pose);
    Matrix out{};
    for (int col = 0; col < 3; ++col) for (int row = 0; row < 3; ++row)
        out[col*4+row] = world[row*4+col];
    for (int row = 0; row < 3; ++row)
        out[12+row] = -(out[row]*pose.position.x + out[4+row]*pose.position.y + out[8+row]*pose.position.z);
    out[15] = 1;
    return out;
}
Matrix projection(const XrFovf& fov) {
    const float left = std::tan(fov.angleLeft), right = std::tan(fov.angleRight);
    const float down = std::tan(fov.angleDown), up = std::tan(fov.angleUp);
    constexpr float nearPlane = .03f, farPlane = 150.f;
    Matrix p{};
    p[0] = 2/(right-left); p[5] = 2/(up-down);
    p[8] = (right+left)/(right-left); p[9] = (up+down)/(up-down);
    p[10] = -(farPlane+nearPlane)/(farPlane-nearPlane); p[11] = -1;
    p[14] = -(2*farPlane*nearPlane)/(farPlane-nearPlane);
    return p;
}
GLuint compile(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr); glCompileShader(shader);
    GLint ok = 0; glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[2048]{}; glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        glDeleteShader(shader); throw std::runtime_error(log);
    }
    return shader;
}
GLuint program(const char* vertex, const char* fragment) {
    GLuint v = compile(GL_VERTEX_SHADER, vertex), f = 0, p = 0;
    try {
        f = compile(GL_FRAGMENT_SHADER, fragment); p = glCreateProgram();
        glAttachShader(p, v); glAttachShader(p, f); glLinkProgram(p);
        GLint ok = 0; glGetProgramiv(p, GL_LINK_STATUS, &ok);
        if (!ok) {
            char log[2048]{}; glGetProgramInfoLog(p, sizeof(log), nullptr, log);
            throw std::runtime_error(log);
        }
        glDeleteShader(v); glDeleteShader(f); return p;
    } catch (...) {
        glDeleteShader(v); if (f) glDeleteShader(f); if (p) glDeleteProgram(p); throw;
    }
}
const char* worldVertex = R"(#version 300 es
layout(location=0) in vec3 aPosition;
layout(location=1) in vec4 aColor;
uniform mat4 uMvp;
out vec4 vColor;
void main(){ gl_Position=uMvp*vec4(aPosition,1.0); vColor=aColor; }
)";
const char* worldFragment = R"(#version 300 es
precision highp float;
in vec4 vColor;
uniform bool uLinearize;
out vec4 outColor;
vec3 linear(vec3 c){return mix(c/12.92,pow((c+0.055)/1.055,vec3(2.4)),step(vec3(0.04045),c));}
void main(){outColor=vec4(uLinearize?linear(vColor.rgb):vColor.rgb,vColor.a);}
)";
// The start exhibit shares the streaming VBO, with W in the otherwise unused opacity lane.
// Project the rotating solid into 3-space first; the two OpenXR cameras then see real stereo.
const char* exhibitVertex = R"(#version 300 es
layout(location=0) in vec3 aPosition;
layout(location=1) in vec4 aMaterial;
uniform mat4 uMvp;
uniform mat4 uProjection4;
uniform vec4 uCenterAndFocal;
out vec3 vPosition;
out vec3 vMaterial;
void main(){
    vec4 point = uProjection4 * vec4(aPosition, aMaterial.a);
    vPosition = uCenterAndFocal.xyz + point.xyz / (uCenterAndFocal.w - point.w);
    vMaterial = aMaterial.rgb;
    gl_Position = uMvp * vec4(vPosition, 1.0);
}
)";
const char* exhibitFragment = R"(#version 300 es
precision highp float;
in vec3 vPosition;
in vec3 vMaterial;
out vec4 outColor;
void main(){
    vec3 n = normalize(cross(dFdx(vPosition), dFdy(vPosition)));
    vec3 base = mix(vMaterial/12.92, pow((vMaterial+.055)/1.055, vec3(2.4)), step(vec3(.04045), vMaterial));
    float key = max(0.0, dot(n, normalize(vec3(-.52,.72,.56))));
    float fill = max(0.0, dot(n, normalize(vec3(.82,.15,-.45))));
    float glint = pow(max(0.0, dot(n, normalize(vec3(-.43,.56,.71)))), 28.0);
    vec3 lit = base * (.38 + .63*key) + vec3(.025,.035,.039)*fill + vec3(.12,.12,.105)*glint;
    outColor = vec4(lit,1.0);
}
)";
const char* uiVertex = R"(#version 300 es
layout(location=0) in vec3 aPosition;
layout(location=1) in vec2 aUv;
uniform mat4 uMvp;
out vec2 vUv;
void main(){gl_Position=uMvp*vec4(aPosition,1.0);vUv=aUv;}
)";
const char* uiFragment = R"(#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uTexture;
uniform bool uLinearize;
uniform float uOpacity;
out vec4 outColor;
vec3 linear(vec3 c){return mix(c/12.92,pow((c+0.055)/1.055,vec3(2.4)),step(vec3(0.04045),c));}
void main(){vec4 c=texture(uTexture,vUv);if(uLinearize && c.a>0.0001)c.rgb=linear(c.rgb/c.a)*c.a;outColor=c*clamp(uOpacity,0.0,1.0);}
)";
struct Swapchain {
    XrSwapchain handle = XR_NULL_HANDLE;
    int width = 0, height = 0;
    GLuint depth = 0;
    std::vector<XrSwapchainImageOpenGLESKHR> images;
};
class Game {
public:
    android_app* app;
    JNIEnv* env = nullptr;
    XrInstance instance = XR_NULL_HANDLE;
    XrSystemId system = XR_NULL_SYSTEM_ID;
    XrSession session = XR_NULL_HANDLE;
    XrSpace space = XR_NULL_HANDLE, headSpace = XR_NULL_HANDLE;
    XrSessionState state = XR_SESSION_STATE_UNKNOWN;
    bool running = false, quit = false, initialized = false;
    bool xrInputFocused = false;
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    EGLConfig config{};
    std::vector<XrView> views;
    std::vector<Swapchain> swapchains;
    GLuint framebuffer = 0, worldProgram = 0, uiProgram = 0, worldVao = 0, worldVbo = 0;
    GLuint uiVao = 0, uiVbo = 0, uiTexture = 0;
    GLuint effectsVao = 0, effectsVbo = 0, exhibitProgram = 0;
    GLint exhibitMvp = -1, exhibitMatrix = -1, exhibitCenter = -1;
    std::array<float, 20> exhibitProjection{};
    bool drawingExhibit = false;
    bool lightEnvironment = true;
    GLsizei worldCount = 0;
    GLsizei effectsCount = 0;
    bool textureReady = false;
    std::vector<float> quads;
    Matrix worldModel = identity;
    jobject bridge = nullptr;
    jmethodID frameMethod = nullptr, focusMethod = nullptr;
    jfieldID verticesField = nullptr, bitmapField = nullptr, quadsField = nullptr, effectsField = nullptr;
    jfieldID worldModelField = nullptr, exhibitProjectionField = nullptr, lightEnvironmentField = nullptr;
    XrTime previousTime = 0;
    uint64_t frames = 0;
    uint64_t textureUploads = 0;
    XrVector3f lastLoggedHead{999,999,999};
    XrGameInput input;
    jmethodID recycleBitmap = nullptr;
    explicit Game(android_app* value): app(value) {}

    void javaCheck(const char* where) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe(); env->ExceptionClear();
            throw std::runtime_error(std::string("Java bridge error: ")+where);
        }
    }
    void initJava() {
        if (app->activity->vm->AttachCurrentThread(&env, nullptr) != JNI_OK)
            throw std::runtime_error("AttachCurrentThread failed");
        jclass activityClass = env->GetObjectClass(app->activity->clazz);
        jmethodID getter = env->GetMethodID(activityClass, "getGameBridge", "()Lcom/fourthward/game/vr/VrGameBridge;");
        javaCheck("getGameBridge lookup");
        jobject local = env->CallObjectMethod(app->activity->clazz, getter); javaCheck("getGameBridge");
        bridge = env->NewGlobalRef(local); env->DeleteLocalRef(local); env->DeleteLocalRef(activityClass);
        jclass bridgeClass = env->GetObjectClass(bridge);
        frameMethod = env->GetMethodID(bridgeClass, "frame", "(F[F[F[F[F)Lcom/fourthward/game/vr/VrRenderFrame;");
        focusMethod = env->GetMethodID(bridgeClass, "setXrFocused", "(Z)V");
        javaCheck("frame lookup"); env->DeleteLocalRef(bridgeClass);
    }
    void initEgl() {
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr))
            throw std::runtime_error("eglInitialize failed");
        eglBindAPI(EGL_OPENGL_ES_API);
        const EGLint attributes[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_DEPTH_SIZE, 0, EGL_NONE};
        EGLint count = 0;
        if (!eglChooseConfig(display, attributes, &config, 1, &count) || count == 0)
            throw std::runtime_error("No GLES3 EGL config");
        const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttributes);
        const EGLint surfaceAttributes[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
        surface = eglCreatePbufferSurface(display, config, surfaceAttributes);
        if (context == EGL_NO_CONTEXT || surface == EGL_NO_SURFACE || !eglMakeCurrent(display, surface, surface, context))
            throw std::runtime_error("GLES3 context creation failed");
        INFO("GL=%s renderer=%s", glGetString(GL_VERSION), glGetString(GL_RENDERER));
    }
    void initialize() {
        initJava();
        PFN_xrInitializeLoaderKHR initializeLoader = nullptr;
        XR_CHECK(xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR", reinterpret_cast<PFN_xrVoidFunction*>(&initializeLoader)));
        XrLoaderInitInfoAndroidKHR loaderInfo{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
        loaderInfo.applicationVM = app->activity->vm; loaderInfo.applicationContext = app->activity->clazz;
        XR_CHECK(initializeLoader(reinterpret_cast<XrLoaderInitInfoBaseHeaderKHR*>(&loaderInfo)));
        uint32_t count = 0;
        XR_CHECK(xrEnumerateInstanceExtensionProperties(nullptr, 0, &count, nullptr));
        std::vector<XrExtensionProperties> available(count, {XR_TYPE_EXTENSION_PROPERTIES});
        XR_CHECK(xrEnumerateInstanceExtensionProperties(nullptr, count, &count, available.data()));
        auto supports = [&](const char* name) {
            return std::any_of(available.begin(), available.end(), [&](const auto& item){return std::strcmp(item.extensionName,name)==0;});
        };
        for (const auto& e: available) INFO("Extension %s", e.extensionName);
        std::vector<const char*> extensions{XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME, XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME};
        for (const char* e: extensions) if (!supports(e)) throw std::runtime_error(std::string("Missing required extension ")+e);
        const bool handTracking = supports(XR_EXT_HAND_TRACKING_EXTENSION_NAME);
        if (handTracking) extensions.push_back(XR_EXT_HAND_TRACKING_EXTENSION_NAME);
        const bool handDataSource = handTracking && supports(XR_EXT_HAND_TRACKING_DATA_SOURCE_EXTENSION_NAME);
        if (handDataSource) extensions.push_back(XR_EXT_HAND_TRACKING_DATA_SOURCE_EXTENSION_NAME);
        if (supports("XR_BD_controller_interaction")) extensions.push_back("XR_BD_controller_interaction");
        XrInstanceCreateInfoAndroidKHR androidInfo{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
        androidInfo.applicationVM = app->activity->vm; androidInfo.applicationActivity = app->activity->clazz;
        XrInstanceCreateInfo create{XR_TYPE_INSTANCE_CREATE_INFO}; create.next = &androidInfo;
        std::strcpy(create.applicationInfo.applicationName, "Fourthward");
        std::strcpy(create.applicationInfo.engineName, "Fourthward Native OpenXR");
        create.applicationInfo.applicationVersion = 1; create.applicationInfo.apiVersion = XR_MAKE_VERSION(1,0,0);
        create.enabledExtensionCount = extensions.size(); create.enabledExtensionNames = extensions.data();
        XR_CHECK(xrCreateInstance(&create, &instance));
        XrInstanceProperties properties{XR_TYPE_INSTANCE_PROPERTIES}; XR_CHECK(xrGetInstanceProperties(instance, &properties));
        INFO("Runtime=%s version=%u.%u.%u", properties.runtimeName, XR_VERSION_MAJOR(properties.runtimeVersion), XR_VERSION_MINOR(properties.runtimeVersion), XR_VERSION_PATCH(properties.runtimeVersion));
        XrSystemGetInfo systemInfo{XR_TYPE_SYSTEM_GET_INFO}; systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
        XR_CHECK(xrGetSystem(instance, &systemInfo, &system));
        PFN_xrGetOpenGLESGraphicsRequirementsKHR requirementsFunction = nullptr;
        XR_CHECK(xrGetInstanceProcAddr(instance, "xrGetOpenGLESGraphicsRequirementsKHR", reinterpret_cast<PFN_xrVoidFunction*>(&requirementsFunction)));
        XrGraphicsRequirementsOpenGLESKHR requirements{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
        XR_CHECK(requirementsFunction(instance, system, &requirements));
        INFO("Required GLES %u.%u to %u.%u", XR_VERSION_MAJOR(requirements.minApiVersionSupported),XR_VERSION_MINOR(requirements.minApiVersionSupported),XR_VERSION_MAJOR(requirements.maxApiVersionSupported),XR_VERSION_MINOR(requirements.maxApiVersionSupported));
        initEgl();
        XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
        binding.display = display; binding.config = config; binding.context = context;
        XrSessionCreateInfo sessionInfo{XR_TYPE_SESSION_CREATE_INFO}; sessionInfo.next = &binding; sessionInfo.systemId = system;
        XR_CHECK(xrCreateSession(instance, &sessionInfo, &session));
        XR_CHECK(xrEnumerateEnvironmentBlendModes(instance, system, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 0, &count, nullptr));
        std::vector<XrEnvironmentBlendMode> blendModes(count);
        XR_CHECK(xrEnumerateEnvironmentBlendModes(instance, system, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,count,&count,blendModes.data()));
        if (std::find(blendModes.begin(), blendModes.end(), XR_ENVIRONMENT_BLEND_MODE_OPAQUE) == blendModes.end())
            throw std::runtime_error("Runtime does not support OPAQUE stereo VR");
        XR_CHECK(xrEnumerateReferenceSpaces(session,0,&count,nullptr));
        std::vector<XrReferenceSpaceType> spaces(count);
        XR_CHECK(xrEnumerateReferenceSpaces(session,count,&count,spaces.data()));
        auto type = std::find(spaces.begin(),spaces.end(),XR_REFERENCE_SPACE_TYPE_STAGE)!=spaces.end()?XR_REFERENCE_SPACE_TYPE_STAGE:XR_REFERENCE_SPACE_TYPE_LOCAL;
        XrReferenceSpaceCreateInfo reference{XR_TYPE_REFERENCE_SPACE_CREATE_INFO}; reference.referenceSpaceType=type; reference.poseInReferenceSpace.orientation.w=1;
        XR_CHECK(xrCreateReferenceSpace(session,&reference,&space));
        INFO("Reference space=%s OPAQUE native VR", type==XR_REFERENCE_SPACE_TYPE_STAGE?"STAGE":"LOCAL");
        reference.referenceSpaceType=XR_REFERENCE_SPACE_TYPE_VIEW;
        XR_CHECK(xrCreateReferenceSpace(session,&reference,&headSpace));
        input.initialize(instance, system, session, handTracking, handDataSource);
        createSwapchains(); createGraphics();
        initialized=true; INFO("Native OpenXR initialized, waiting for READY");
    }
    void createSwapchains() {
        uint32_t count=0;
        XR_CHECK(xrEnumerateViewConfigurationViews(instance,system,XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,0,&count,nullptr));
        if(count!=2)throw std::runtime_error("Expected stereo view count 2");
        std::vector<XrViewConfigurationView> configurations(count,{XR_TYPE_VIEW_CONFIGURATION_VIEW});
        XR_CHECK(xrEnumerateViewConfigurationViews(instance,system,XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,count,&count,configurations.data()));
        views.resize(count,{XR_TYPE_VIEW}); swapchains.resize(count);
        uint32_t formatCount=0;XR_CHECK(xrEnumerateSwapchainFormats(session,0,&formatCount,nullptr));
        std::vector<int64_t> formats(formatCount);XR_CHECK(xrEnumerateSwapchainFormats(session,formatCount,&formatCount,formats.data()));
        // Prefer sRGB storage to avoid banding in the dark environment; the fragment shaders
        // still output linear light, which the GLES sRGB framebuffer encodes automatically.
        int64_t format=GL_SRGB8_ALPHA8;
        if(std::find(formats.begin(),formats.end(),format)==formats.end())format=GL_RGBA8;
        if(std::find(formats.begin(),formats.end(),format)==formats.end())throw std::runtime_error("No RGBA8 OpenXR swapchain format");
        for(size_t eye=0;eye<swapchains.size();++eye){
            auto& target=swapchains[eye];const auto& rec=configurations[eye];
            target.width=rec.recommendedImageRectWidth;target.height=rec.recommendedImageRectHeight;
            XrSwapchainCreateInfo info{XR_TYPE_SWAPCHAIN_CREATE_INFO};
            info.usageFlags=XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;info.format=format;
            info.sampleCount=1;info.width=target.width;info.height=target.height;info.faceCount=1;info.arraySize=1;info.mipCount=1;
            XR_CHECK(xrCreateSwapchain(session,&info,&target.handle));
            uint32_t imageCount=0;XR_CHECK(xrEnumerateSwapchainImages(target.handle,0,&imageCount,nullptr));
            target.images.resize(imageCount,{XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
            XR_CHECK(xrEnumerateSwapchainImages(target.handle,imageCount,&imageCount,reinterpret_cast<XrSwapchainImageBaseHeader*>(target.images.data())));
            glGenRenderbuffers(1,&target.depth);glBindRenderbuffer(GL_RENDERBUFFER,target.depth);
            glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH_COMPONENT24,target.width,target.height);
            INFO("Eye %zu swapchain=%dx%d images=%u format=%lld",eye,target.width,target.height,imageCount,static_cast<long long>(format));
        }
    }
    void createGraphics(){
        worldProgram=program(worldVertex,worldFragment);uiProgram=program(uiVertex,uiFragment);
        exhibitProgram=program(exhibitVertex,exhibitFragment);
        exhibitMvp=glGetUniformLocation(exhibitProgram,"uMvp");
        exhibitMatrix=glGetUniformLocation(exhibitProgram,"uProjection4");
        exhibitCenter=glGetUniformLocation(exhibitProgram,"uCenterAndFocal");
        glGenFramebuffers(1,&framebuffer);
        glGenVertexArrays(1,&worldVao);glGenBuffers(1,&worldVbo);glBindVertexArray(worldVao);glBindBuffer(GL_ARRAY_BUFFER,worldVbo);
        glEnableVertexAttribArray(0);glVertexAttribPointer(0,3,GL_FLOAT,GL_FALSE,7*sizeof(float),nullptr);
        glEnableVertexAttribArray(1);glVertexAttribPointer(1,4,GL_FLOAT,GL_FALSE,7*sizeof(float),reinterpret_cast<void*>(3*sizeof(float)));
        glGenVertexArrays(1,&effectsVao);glGenBuffers(1,&effectsVbo);glBindVertexArray(effectsVao);glBindBuffer(GL_ARRAY_BUFFER,effectsVbo);
        glEnableVertexAttribArray(0);glVertexAttribPointer(0,3,GL_FLOAT,GL_FALSE,7*sizeof(float),nullptr);
        glEnableVertexAttribArray(1);glVertexAttribPointer(1,4,GL_FLOAT,GL_FALSE,7*sizeof(float),reinterpret_cast<void*>(3*sizeof(float)));
        glGenVertexArrays(1,&uiVao);glGenBuffers(1,&uiVbo);glBindVertexArray(uiVao);glBindBuffer(GL_ARRAY_BUFFER,uiVbo);
        glEnableVertexAttribArray(0);glVertexAttribPointer(0,3,GL_FLOAT,GL_FALSE,5*sizeof(float),nullptr);
        glEnableVertexAttribArray(1);glVertexAttribPointer(1,2,GL_FLOAT,GL_FALSE,5*sizeof(float),reinterpret_cast<void*>(3*sizeof(float)));
        glGenTextures(1,&uiTexture);glBindTexture(GL_TEXTURE_2D,uiTexture);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        // Diagnostic geometry is replaced atomically by the bridge's first world snapshot.
        const float triangle[]={-.5f,1,-2,1,.85f,0,1, .5f,1,-2,.2f,.8f,.8f,1, 0,2,-2,1,1,1,1};
        glBindBuffer(GL_ARRAY_BUFFER,worldVbo);glBufferData(GL_ARRAY_BUFFER,sizeof(triangle),triangle,GL_STATIC_DRAW);worldCount=3;
        glBindVertexArray(0);
    }
    void setInputFocus(bool focused){
        if(xrInputFocused==focused)return;
        xrInputFocused=focused;
        if(bridge&&focusMethod){env->CallVoidMethod(bridge,focusMethod,focused?JNI_TRUE:JNI_FALSE);javaCheck("XR input focus");}
    }
    void events(){
        XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
        while(xrPollEvent(instance,&event)==XR_SUCCESS){
            if(event.type==XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED){
                const auto& changed=*reinterpret_cast<XrEventDataSessionStateChanged*>(&event);state=changed.state;
                INFO("Session state=%d",state);
                if(state==XR_SESSION_STATE_READY){XrSessionBeginInfo begin{XR_TYPE_SESSION_BEGIN_INFO};begin.primaryViewConfigurationType=XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;XR_CHECK(xrBeginSession(session,&begin));running=true;previousTime=0;}
                else if(state==XR_SESSION_STATE_STOPPING){running=false;XR_CHECK(xrEndSession(session));}
                else if(state==XR_SESSION_STATE_EXITING||state==XR_SESSION_STATE_LOSS_PENDING){running=false;quit=true;}
                // FOCUSED is restored only with valid views AND a valid head pose below.
                // Android window focus remains an independent condition in the bridge.
                if(state!=XR_SESSION_STATE_FOCUSED)setInputFocus(false);
            }else if(event.type==XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING){quit=true;}
            event={XR_TYPE_EVENT_DATA_BUFFER};
        }
    }
    jfloatArray array(const float* values,size_t count){
        auto result=env->NewFloatArray(static_cast<jsize>(count));
        if(!result)throw std::runtime_error("Java float array allocation failed");
        env->SetFloatArrayRegion(result,0,static_cast<jsize>(count),values);return result;
    }
    void updateBridge(XrTime time){
        env->PushLocalFrame(24);
        try{
            XrSpaceLocation head{XR_TYPE_SPACE_LOCATION};XR_CHECK(xrLocateSpace(headSpace,space,time,&head));
            jfloatArray javaHead=nullptr;
            const auto valid=XR_SPACE_LOCATION_POSITION_VALID_BIT|XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
            setInputFocus(state==XR_SESSION_STATE_FOCUSED&&(head.locationFlags&valid)==valid);
            if((head.locationFlags&valid)==valid){
                const auto& p=head.pose.position;const auto& q=head.pose.orientation;
                const float packed[]={p.x,p.y,p.z,q.x,q.y,q.z,q.w};javaHead=array(packed,7);
                if(frames%90==0 || std::abs(p.x-lastLoggedHead.x)+std::abs(p.y-lastLoggedHead.y)+std::abs(p.z-lastLoggedHead.z)>.25f){
                    INFO("Pose frame=%llu valid=1 xyz=(%.3f,%.3f,%.3f) q=(%.3f,%.3f,%.3f,%.3f)",static_cast<unsigned long long>(frames),p.x,p.y,p.z,q.x,q.y,q.z,q.w);lastLoggedHead=p;
                }
            }else if(frames%90==0)INFO("Pose valid=0 flags=%llu",static_cast<unsigned long long>(head.locationFlags));
            const auto sample = input.sample(space,time,xrInputFocused);
            jfloatArray leftHand = sample.handValid[0] ? array(sample.joints[0].data(),sample.joints[0].size()) : nullptr;
            jfloatArray rightHand = sample.handValid[1] ? array(sample.joints[1].data(),sample.joints[1].size()) : nullptr;
            const float dt=previousTime?std::clamp(static_cast<float>((time-previousTime)*1e-9),0.f,.05f):1.f/72.f;
            previousTime=time;
            jobject frame=env->CallObjectMethod(bridge,frameMethod,dt,javaHead,leftHand,rightHand,array(sample.controls.data(),6));javaCheck("frame");
            if(!frame)throw std::runtime_error("VrGameBridge.frame returned null");
            if(!verticesField){
                jclass klass=env->GetObjectClass(frame);
                verticesField=env->GetFieldID(klass,"worldVertices","[F");bitmapField=env->GetFieldID(klass,"uiBitmap","Landroid/graphics/Bitmap;");quadsField=env->GetFieldID(klass,"uiQuads","[F");effectsField=env->GetFieldID(klass,"effectsVertices","[F");
                worldModelField=env->GetFieldID(klass,"worldModel","[F");
                exhibitProjectionField=env->GetFieldID(klass,"exhibitProjection","[F");
                lightEnvironmentField=env->GetFieldID(klass,"lightEnvironment","Z");javaCheck("frame fields");
            }
            auto vertices=static_cast<jfloatArray>(env->GetObjectField(frame,verticesField));
            if(vertices){
                const jsize n=env->GetArrayLength(vertices);
                if(n%21!=0)throw std::runtime_error("worldVertices must be xyzrgba triangles");
                auto model=static_cast<jfloatArray>(env->GetObjectField(frame,worldModelField));
                worldModel=identity;
                if(model){
                    const auto size=static_cast<jsize>(worldModel.size());
                    if(env->GetArrayLength(model)!=size)throw std::runtime_error("worldModel must be a 4x4 matrix");
                    env->GetFloatArrayRegion(model,0,size,worldModel.data());
                }
                jfloat* data=env->GetFloatArrayElements(vertices,nullptr);
                glBindBuffer(GL_ARRAY_BUFFER,worldVbo);glBufferData(GL_ARRAY_BUFFER,n*sizeof(float),data,GL_DYNAMIC_DRAW);
                env->ReleaseFloatArrayElements(vertices,data,JNI_ABORT);worldCount=n/7;
            }
            lightEnvironment=env->GetBooleanField(frame,lightEnvironmentField);
            auto exhibit=static_cast<jfloatArray>(env->GetObjectField(frame,exhibitProjectionField));
            drawingExhibit=exhibit!=nullptr;
            if(exhibit){
                const auto size=static_cast<jsize>(exhibitProjection.size());
                if(env->GetArrayLength(exhibit)!=size)throw std::runtime_error("exhibitProjection requires a 4D matrix and center/focal vector");
                env->GetFloatArrayRegion(exhibit,0,size,exhibitProjection.data());
            }
            auto effects=static_cast<jfloatArray>(env->GetObjectField(frame,effectsField));
            if(effects){
                const jsize n=env->GetArrayLength(effects);
                if(n%21!=0)throw std::runtime_error("effectsVertices must be xyzrgba triangles");
                effectsCount=n/7;
                if(n){
                    jfloat* data=env->GetFloatArrayElements(effects,nullptr);
                    glBindBuffer(GL_ARRAY_BUFFER,effectsVbo);glBufferData(GL_ARRAY_BUFFER,n*sizeof(float),data,drawingExhibit?GL_STATIC_DRAW:GL_STREAM_DRAW);
                    env->ReleaseFloatArrayElements(effects,data,JNI_ABORT);
                }
            }
            jobject bitmap=env->GetObjectField(frame,bitmapField);
            if(bitmap){
                AndroidBitmapInfo info{};
                if(AndroidBitmap_getInfo(env,bitmap,&info)!=ANDROID_BITMAP_RESULT_SUCCESS||info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888)
                    throw std::runtime_error("UI bitmap must be ARGB_8888");
                void* pixels=nullptr;
                if(AndroidBitmap_lockPixels(env,bitmap,&pixels)!=ANDROID_BITMAP_RESULT_SUCCESS)throw std::runtime_error("UI bitmap lock failed");
                glBindTexture(GL_TEXTURE_2D,uiTexture);glPixelStorei(GL_UNPACK_ALIGNMENT,4);glPixelStorei(GL_UNPACK_ROW_LENGTH,info.stride/4);
                glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,info.width,info.height,0,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
                ++textureUploads;
                glPixelStorei(GL_UNPACK_ROW_LENGTH,0);AndroidBitmap_unlockPixels(env,bitmap);textureReady=true;
                if(!recycleBitmap){jclass bitmapClass=env->GetObjectClass(bitmap);recycleBitmap=env->GetMethodID(bitmapClass,"recycle","()V");env->DeleteLocalRef(bitmapClass);}
                env->CallVoidMethod(bitmap,recycleBitmap);javaCheck("bitmap recycle");
            }
            auto javaQuads=static_cast<jfloatArray>(env->GetObjectField(frame,quadsField));
            const jsize n=javaQuads?env->GetArrayLength(javaQuads):0;
            if(n%uiQuadStride!=0)throw std::runtime_error("uiQuads stride must be 21");
            quads.resize(n);if(n)env->GetFloatArrayRegion(javaQuads,0,n,quads.data());
            javaCheck("frame arrays");env->PopLocalFrame(nullptr);
        }catch(...){env->PopLocalFrame(nullptr);throw;}
    }
    void drawEye(size_t eye,uint32_t imageIndex){
        auto& target=swapchains[eye];
        glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,target.images[imageIndex].image,0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_RENDERBUFFER,target.depth);
        if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw std::runtime_error("Incomplete eye framebuffer");
        glViewport(0,0,target.width,target.height);glDisable(GL_SCISSOR_TEST);glDisable(GL_CULL_FACE);
        // OpenXR interprets RGBA8 as linear light. An sRGB target applies its own transfer
        // encoding; in either case authored sRGB inputs must be decoded before shading.
        // The open stage is neutral slate so white walls and teaching wires keep their silhouettes.
        if(lightEnvironment) glClearColor(.095f,.115f,.117f,1.f);
        else glClearColor(.0056f,.0075f,.007f,1.f);
        glDepthMask(GL_TRUE);glClearDepthf(1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glEnable(GL_BLEND);glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);
        Matrix viewProjection=multiply(projection(views[eye].fov),inversePose(views[eye].pose));
        Matrix worldMvp=multiply(viewProjection,worldModel);
        glUseProgram(worldProgram);glUniformMatrix4fv(glGetUniformLocation(worldProgram,"uMvp"),1,GL_FALSE,worldMvp.data());
        glUniform1i(glGetUniformLocation(worldProgram,"uLinearize"),GL_TRUE);
        glBindVertexArray(worldVao);glDrawArrays(GL_TRIANGLES,0,worldCount);
        if(effectsCount){
            if(drawingExhibit){
                glUseProgram(exhibitProgram);
                glUniformMatrix4fv(exhibitMvp,1,GL_FALSE,worldMvp.data());
                glUniformMatrix4fv(exhibitMatrix,1,GL_FALSE,exhibitProjection.data());
                glUniform4fv(exhibitCenter,1,exhibitProjection.data()+16);
            }
            // Opaque exhibit pieces occlude each other. Gameplay markers only read depth.
            glDepthMask(drawingExhibit?GL_TRUE:GL_FALSE);
            glBindVertexArray(effectsVao);glDrawArrays(GL_TRIANGLES,0,effectsCount);
        }
        if(textureReady&&!quads.empty()){
            glUseProgram(uiProgram);glUniform1i(glGetUniformLocation(uiProgram,"uLinearize"),GL_TRUE);glUniform1i(glGetUniformLocation(uiProgram,"uTexture"),0);
            glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,uiTexture);glBindVertexArray(uiVao);glBindBuffer(GL_ARRAY_BUFFER,uiVbo);
            // Panels and HUD draw last in tracking space, above every scene object.
            glBlendFunc(GL_ONE,GL_ONE_MINUS_SRC_ALPHA);glDepthMask(GL_FALSE);glDisable(GL_DEPTH_TEST);
            for(size_t i=0;i<quads.size();i+=uiQuadStride){
                const float* record=quads.data()+i;Matrix model{};std::copy(record,record+16,model.begin());
                const Matrix mvp=multiply(viewProjection,model);glUniformMatrix4fv(glGetUniformLocation(uiProgram,"uMvp"),1,GL_FALSE,mvp.data());
                glUniform1f(glGetUniformLocation(uiProgram,"uOpacity"),record[20]);
                const float l=record[16],t=record[17],r=record[18],b=record[19];
                const float data[]={-.5f,.5f,0,l,t, -.5f,-.5f,0,l,b, .5f,-.5f,0,r,b,
                                     -.5f,.5f,0,l,t, .5f,-.5f,0,r,b, .5f,.5f,0,r,t};
                glBufferData(GL_ARRAY_BUFFER,sizeof(data),data,GL_STREAM_DRAW);glDrawArrays(GL_TRIANGLES,0,6);
            }
        }
        glDepthMask(GL_TRUE);glBindVertexArray(0);glBindFramebuffer(GL_FRAMEBUFFER,0);
    }
    void render(){
        XrFrameWaitInfo wait{XR_TYPE_FRAME_WAIT_INFO};XrFrameState timing{XR_TYPE_FRAME_STATE};XR_CHECK(xrWaitFrame(session,&wait,&timing));
        XrFrameBeginInfo begin{XR_TYPE_FRAME_BEGIN_INFO};XR_CHECK(xrBeginFrame(session,&begin));
        std::vector<XrCompositionLayerProjectionView> projectionViews(views.size(),{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW});
        XrCompositionLayerProjection layer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};layer.space=space;
        bool rendered=false;
        if(timing.shouldRender){
            XrViewLocateInfo locate{XR_TYPE_VIEW_LOCATE_INFO};locate.viewConfigurationType=XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;locate.displayTime=timing.predictedDisplayTime;locate.space=space;
            XrViewState viewState{XR_TYPE_VIEW_STATE};uint32_t count=0;
            XR_CHECK(xrLocateViews(session,&locate,&viewState,views.size(),&count,views.data()));
            const auto valid=XR_VIEW_STATE_POSITION_VALID_BIT|XR_VIEW_STATE_ORIENTATION_VALID_BIT;
            if((viewState.viewStateFlags&valid)==valid&&count==views.size()){
                updateBridge(timing.predictedDisplayTime);
                for(size_t eye=0;eye<views.size();++eye){
                    auto& target=swapchains[eye];uint32_t index=0;XrSwapchainImageAcquireInfo acquire{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};XR_CHECK(xrAcquireSwapchainImage(target.handle,&acquire,&index));
                    XrSwapchainImageWaitInfo waitImage{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};waitImage.timeout=XR_INFINITE_DURATION;XR_CHECK(xrWaitSwapchainImage(target.handle,&waitImage));
                    drawEye(eye,index);glFlush();
                    XrSwapchainImageReleaseInfo release{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};XR_CHECK(xrReleaseSwapchainImage(target.handle,&release));
                    auto& view=projectionViews[eye];view.pose=views[eye].pose;view.fov=views[eye].fov;
                    view.subImage.swapchain=target.handle;view.subImage.imageRect={{0,0},{target.width,target.height}};
                }
                layer.viewCount=projectionViews.size();layer.views=projectionViews.data();rendered=true;
            }
        }
        if(!rendered){setInputFocus(false);previousTime=0;}
        const XrCompositionLayerBaseHeader* layers[]={reinterpret_cast<XrCompositionLayerBaseHeader*>(&layer)};
        XrFrameEndInfo end{XR_TYPE_FRAME_END_INFO};end.displayTime=timing.predictedDisplayTime;end.environmentBlendMode=XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
        end.layerCount=rendered?1:0;end.layers=rendered?layers:nullptr;XR_CHECK(xrEndFrame(session,&end));
        ++frames;if(frames==1||frames%300==0)INFO("Frame submitted=%llu rendered=%d state=%d worldVertices=%d effectsVertices=%d quads=%zu atlasUploads=%llu GLerror=0x%x",static_cast<unsigned long long>(frames),rendered,state,worldCount,effectsCount,quads.size()/22,static_cast<unsigned long long>(textureUploads),glGetError());
    }
    void close(){
        if(session&&running){xrRequestExitSession(session);running=false;}
        input.close();
        if(display!=EGL_NO_DISPLAY&&context!=EGL_NO_CONTEXT){
            for(auto& target:swapchains)if(target.depth)glDeleteRenderbuffers(1,&target.depth);
            if(framebuffer)glDeleteFramebuffers(1,&framebuffer);
            if(worldVbo)glDeleteBuffers(1,&worldVbo);if(uiVbo)glDeleteBuffers(1,&uiVbo);
            if(effectsVbo)glDeleteBuffers(1,&effectsVbo);
            if(worldVao)glDeleteVertexArrays(1,&worldVao);if(uiVao)glDeleteVertexArrays(1,&uiVao);
            if(effectsVao)glDeleteVertexArrays(1,&effectsVao);
            if(exhibitProgram)glDeleteProgram(exhibitProgram);
            if(uiTexture)glDeleteTextures(1,&uiTexture);if(worldProgram)glDeleteProgram(worldProgram);if(uiProgram)glDeleteProgram(uiProgram);
        }
        for(auto& target:swapchains)if(target.handle)xrDestroySwapchain(target.handle);
        if(headSpace)xrDestroySpace(headSpace);if(space)xrDestroySpace(space);
        if(session)xrDestroySession(session);if(instance)xrDestroyInstance(instance);
        if(display!=EGL_NO_DISPLAY){eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);if(surface!=EGL_NO_SURFACE)eglDestroySurface(display,surface);if(context!=EGL_NO_CONTEXT)eglDestroyContext(display,context);eglTerminate(display);}
        if(env&&bridge)env->DeleteGlobalRef(bridge);if(env)app->activity->vm->DetachCurrentThread();
    }
};
} // namespace

void android_main(android_app* app){
    app_dummy();
    Game game(app);
    try{
        // NativeActivity creates a normal Android lifecycle host; OpenXR owns the immersive views.
        while(!app->window&&!app->destroyRequested){
            android_poll_source* source=nullptr;int events=0;
            if(ALooper_pollOnce(-1,nullptr,&events,reinterpret_cast<void**>(&source))>=0&&source)source->process(app,source);
        }
        if(!app->destroyRequested)game.initialize();
        while(!app->destroyRequested&&!game.quit){
            android_poll_source* source=nullptr;int events=0;
            while(ALooper_pollOnce(game.running?0:50,nullptr,&events,reinterpret_cast<void**>(&source))>=0){
                if(source)source->process(app,source);
                if(app->destroyRequested)break;
                if(!game.running)break;
            }
            if(app->destroyRequested)break;
            game.events();if(game.running)game.render();
        }
    }catch(const std::exception& error){ERROR("Fatal native OpenXR error: %s",error.what());ANativeActivity_finish(app->activity);}
    game.close();INFO("Native OpenXR resources closed");
}
