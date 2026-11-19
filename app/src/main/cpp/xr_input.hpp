#pragma once
#include <openxr/openxr.h>
#include <android/log.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>

/** Standard OpenXR actions and optional EXT hand joints; all positions share the scene space. */
class XrGameInput {
public:
    static constexpr size_t jointPositionFloats = XR_HAND_JOINT_COUNT_EXT * 3;
    struct Sample {
        std::array<float, 6> controls{};
        // Positions followed by the optional palm orientation, xyzw. All zero means unavailable.
        std::array<std::array<float, jointPositionFloats + 4>, 2> joints{};
        std::array<bool, 2> handValid{};
    };
    XrInstance instance = XR_NULL_HANDLE;
    XrSession session = XR_NULL_HANDLE;
    XrActionSet actionSet = XR_NULL_HANDLE;
    XrAction aim = XR_NULL_HANDLE, stick = XR_NULL_HANDLE, trigger = XR_NULL_HANDLE;
    XrAction select = XR_NULL_HANDLE;
    XrAction grip = XR_NULL_HANDLE, activate = XR_NULL_HANDLE, menu = XR_NULL_HANDLE, stickClick = XR_NULL_HANDLE;
    std::array<XrPath, 2> handPaths{};
    std::array<XrSpace, 2> aimSpaces{};
    std::array<XrHandTrackerEXT, 2> trackers{};
    PFN_xrCreateHandTrackerEXT createTracker = nullptr;
    PFN_xrDestroyHandTrackerEXT destroyTracker = nullptr;
    PFN_xrLocateHandJointsEXT locateJoints = nullptr;
    bool handsSupported = false;
    bool handDataSourceSupported = false;
    std::array<bool, 2> suppressPointerClick{};
    uint64_t samples = 0;

    static void require(XrResult result, const char* operation) {
        if (XR_FAILED(result)) throw std::runtime_error(std::string(operation)+": "+std::to_string(result));
    }
    XrPath path(const char* name) {
        XrPath value = XR_NULL_PATH; require(xrStringToPath(instance,name,&value),name);return value;
    }
    XrAction action(const char* name, const char* label, XrActionType type) {
        XrActionCreateInfo info{XR_TYPE_ACTION_CREATE_INFO};
        std::strncpy(info.actionName,name,XR_MAX_ACTION_NAME_SIZE-1);
        std::strncpy(info.localizedActionName,label,XR_MAX_LOCALIZED_ACTION_NAME_SIZE-1);
        info.actionType=type;info.countSubactionPaths=handPaths.size();info.subactionPaths=handPaths.data();
        XrAction out=XR_NULL_HANDLE;require(xrCreateAction(actionSet,&info,&out),name);return out;
    }
    void suggest(const char* profile, bool simple) {
        std::vector<XrActionSuggestedBinding> bindings;
        auto add=[&](XrAction a,int hand,const char* component){
            std::string name=hand==0?"/user/hand/left":"/user/hand/right";name+=component;
            bindings.push_back({a,path(name.c_str())});
        };
        for(int hand=0;hand<2;++hand){
            add(aim,hand,"/input/aim/pose");
            if(simple){add(select,hand,"/input/select/click");}
            else{
                add(stick,hand,"/input/thumbstick");add(trigger,hand,"/input/trigger/value");
                add(grip,hand,"/input/squeeze/value");add(stickClick,hand,"/input/thumbstick/click");
            }
        }
        if(simple){add(menu,0,"/input/menu/click");}
        else{add(menu,0,"/input/x/click");add(activate,1,"/input/a/click");}
        // B, Y and system buttons remain reserved for the operating system.
        XrInteractionProfileSuggestedBinding info{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
        info.interactionProfile=path(profile);info.countSuggestedBindings=bindings.size();info.suggestedBindings=bindings.data();
        XrResult result=xrSuggestInteractionProfileBindings(instance,&info);
        __android_log_print(ANDROID_LOG_INFO,"FourthwardXR","Controller profile %s result=%d",profile,result);
    }
    void initialize(XrInstance inInstance,XrSystemId system,XrSession inSession,bool enableHands, bool enableHandDataSource) {
        instance=inInstance;session=inSession;
        handDataSourceSupported = enableHandDataSource;
        handPaths={path("/user/hand/left"),path("/user/hand/right")};
        XrActionSetCreateInfo setInfo{XR_TYPE_ACTION_SET_CREATE_INFO};
        std::strcpy(setInfo.actionSetName,"fourthward");std::strcpy(setInfo.localizedActionSetName,"Fourthward game controls");
        require(xrCreateActionSet(instance,&setInfo,&actionSet),"xrCreateActionSet");
        aim=action("aim","Controller aim",XR_ACTION_TYPE_POSE_INPUT);
        stick=action("stick","Move and rotate",XR_ACTION_TYPE_VECTOR2F_INPUT);
        trigger=action("trigger","Select and activate",XR_ACTION_TYPE_FLOAT_INPUT);
        grip=action("grip","Reference and reset",XR_ACTION_TYPE_FLOAT_INPUT);
        select=action("select","Select with pointer",XR_ACTION_TYPE_BOOLEAN_INPUT);
        activate=action("activate","Activate control",XR_ACTION_TYPE_BOOLEAN_INPUT);
        menu=action("menu","Toggle control panel",XR_ACTION_TYPE_BOOLEAN_INPUT);
        stickClick=action("stick_click","Restart and next level",XR_ACTION_TYPE_BOOLEAN_INPUT);
        suggest("/interaction_profiles/khr/simple_controller",true);
        suggest("/interaction_profiles/oculus/touch_controller",false);
        suggest("/interaction_profiles/bytedance/pico4_controller",false);
        suggest("/interaction_profiles/bytedance/pico_neo3_controller",false);
        suggest("/interaction_profiles/bytedance/pico_ultra_controller_bd",false);
        XrSessionActionSetsAttachInfo attach{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};attach.countActionSets=1;attach.actionSets=&actionSet;
        require(xrAttachSessionActionSets(session,&attach),"xrAttachSessionActionSets");
        for(int hand=0;hand<2;++hand){
            XrActionSpaceCreateInfo info{XR_TYPE_ACTION_SPACE_CREATE_INFO};info.action=aim;info.subactionPath=handPaths[hand];info.poseInActionSpace.orientation.w=1;
            require(xrCreateActionSpace(session,&info,&aimSpaces[hand]),"xrCreateActionSpace");
        }
        if(enableHands){
            XrSystemHandTrackingPropertiesEXT handProperties{XR_TYPE_SYSTEM_HAND_TRACKING_PROPERTIES_EXT};
            XrSystemProperties properties{XR_TYPE_SYSTEM_PROPERTIES};properties.next=&handProperties;
            if(XR_SUCCEEDED(xrGetSystemProperties(instance,system,&properties))&&handProperties.supportsHandTracking){
                require(xrGetInstanceProcAddr(instance,"xrCreateHandTrackerEXT",reinterpret_cast<PFN_xrVoidFunction*>(&createTracker)),"hand create function");
                require(xrGetInstanceProcAddr(instance,"xrDestroyHandTrackerEXT",reinterpret_cast<PFN_xrVoidFunction*>(&destroyTracker)),"hand destroy function");
                require(xrGetInstanceProcAddr(instance,"xrLocateHandJointsEXT",reinterpret_cast<PFN_xrVoidFunction*>(&locateJoints)),"hand locate function");
                handsSupported=true;
            }
        }
        __android_log_print(ANDROID_LOG_INFO,"FourthwardXR","EXT hand tracking supported=%d",handsSupported);
    }
    bool boolean(XrAction which,int hand){
        XrActionStateGetInfo info{XR_TYPE_ACTION_STATE_GET_INFO};info.action=which;info.subactionPath=handPaths[hand];
        XrActionStateBoolean state{XR_TYPE_ACTION_STATE_BOOLEAN};
        return XR_SUCCEEDED(xrGetActionStateBoolean(session,&info,&state))&&state.isActive&&state.currentState;
    }
    float scalar(XrAction which,int hand){
        XrActionStateGetInfo info{XR_TYPE_ACTION_STATE_GET_INFO};info.action=which;info.subactionPath=handPaths[hand];
        XrActionStateFloat state{XR_TYPE_ACTION_STATE_FLOAT};
        return XR_SUCCEEDED(xrGetActionStateFloat(session,&info,&state))&&state.isActive?state.currentState:0.f;
    }
    Sample sample(XrSpace base,XrTime time,bool focused){
        Sample out; ++samples;
        if(!focused)return out;
        XrActiveActionSet active{actionSet,XR_NULL_PATH};XrActionsSyncInfo sync{XR_TYPE_ACTIONS_SYNC_INFO};sync.countActiveActionSets=1;sync.activeActionSets=&active;
        int mask = 0;
        std::array<bool, 2> selectPressed{}, triggerPressed{};
        if(XR_SUCCEEDED(xrSyncActions(session,&sync))){
            for(int hand=0;hand<2;++hand){
                XrActionStateGetInfo info{XR_TYPE_ACTION_STATE_GET_INFO};info.action=aim;info.subactionPath=handPaths[hand];
                XrActionStatePose pose{XR_TYPE_ACTION_STATE_POSE};XrSpaceLocation location{XR_TYPE_SPACE_LOCATION};
                bool connected=XR_SUCCEEDED(xrGetActionStatePose(session,&info,&pose))&&pose.isActive&&XR_SUCCEEDED(xrLocateSpace(aimSpaces[hand],base,time,&location))&&(location.locationFlags&XR_SPACE_LOCATION_ORIENTATION_VALID_BIT);
                out.controls[hand]=connected?1.f:0.f;
                if(!connected)continue;
                info.action=stick;XrActionStateVector2f axis{XR_TYPE_ACTION_STATE_VECTOR2F};
                if(XR_SUCCEEDED(xrGetActionStateVector2f(session,&info,&axis))&&axis.isActive){
                    if(hand==0){out.controls[2]=axis.currentState.x;out.controls[3]=axis.currentState.y;}
                    else out.controls[4]=axis.currentState.x;
                }
                if(boolean(menu,hand))mask|=1;
                if(boolean(activate,hand))mask|=2;
                selectPressed[hand] = boolean(select,hand);
                triggerPressed[hand] = scalar(trigger,hand) > .65f;
                if(scalar(grip,hand)>.65f)mask|=hand==0?8:4;
                if(boolean(stickClick,hand))mask|=hand==0?16:32;
            }
        }
        if(handsSupported){
            for(int hand=0;hand<2;++hand){
                if(!trackers[hand]&&(samples==1||samples%180==0)){
                    XrHandTrackerCreateInfoEXT info{XR_TYPE_HAND_TRACKER_CREATE_INFO_EXT};info.hand=hand==0?XR_HAND_LEFT_EXT:XR_HAND_RIGHT_EXT;info.handJointSet=XR_HAND_JOINT_SET_DEFAULT_EXT;
                    // Controller-derived joint poses describe the hand wrapped around the hardware.
                    // Request optical hands when the runtime can distinguish the two sources.
                    XrHandTrackingDataSourceEXT source = XR_HAND_TRACKING_DATA_SOURCE_UNOBSTRUCTED_EXT;
                    XrHandTrackingDataSourceInfoEXT dataSource{XR_TYPE_HAND_TRACKING_DATA_SOURCE_INFO_EXT};
                    dataSource.requestedDataSourceCount = 1;
                    dataSource.requestedDataSources = &source;
                    if (handDataSourceSupported) info.next = &dataSource;
                    XrResult result=createTracker(session,&info,&trackers[hand]);
                    __android_log_print(ANDROID_LOG_INFO,"FourthwardXR","Hand tracker hand=%d result=%d",hand,result);
                }
                if(!trackers[hand])continue;
                std::array<XrHandJointLocationEXT,XR_HAND_JOINT_COUNT_EXT> joints{};
                XrHandJointLocationsEXT locations{XR_TYPE_HAND_JOINT_LOCATIONS_EXT};locations.jointCount=joints.size();locations.jointLocations=joints.data();
                XrHandJointsLocateInfoEXT locate{XR_TYPE_HAND_JOINTS_LOCATE_INFO_EXT};locate.baseSpace=base;locate.time=time;
                if(XR_FAILED(locateJoints(trackers[hand],&locate,&locations))||!locations.isActive)continue;
                // Let the gesture adapter decide which joints it needs. Losing a distal joint
                // or an unused metacarpal must not cancel a tracked thumb/index drag.
                for (size_t joint = 0; joint < joints.size(); ++joint) {
                    const auto& location = joints[joint];
                    const auto& p = location.pose.position;
                    auto* xyz = out.joints[hand].data() + joint * 3;
                    if ((location.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) &&
                        std::isfinite(p.x) && std::isfinite(p.y) && std::isfinite(p.z)) {
                        xyz[0] = p.x;
                        xyz[1] = p.y;
                        xyz[2] = p.z;
                    } else {
                        std::fill_n(xyz, 3, std::numeric_limits<float>::quiet_NaN());
                    }
                }
                const auto& positions = out.joints[hand];
                out.handValid[hand] = std::isfinite(positions[XR_HAND_JOINT_THUMB_TIP_EXT * 3]) &&
                                      std::isfinite(positions[XR_HAND_JOINT_INDEX_TIP_EXT * 3]);
                const auto& palm = joints[XR_HAND_JOINT_PALM_EXT];
                if (!(palm.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT)) continue;
                const auto& q = palm.pose.orientation;
                // Position-only streams can fill every joint rotation with the same placeholder.
                const bool uniformRotations = std::all_of(joints.begin(), joints.end(), [&](const auto& joint) {
                    const auto& r = joint.pose.orientation;
                    return (joint.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) &&
                           std::abs(q.x*r.x + q.y*r.y + q.z*r.z + q.w*r.w) >= 0.99999f;
                });
                if (!uniformRotations) {
                    auto* orientation = out.joints[hand].data() + jointPositionFloats;
                    orientation[0] = q.x;
                    orientation[1] = q.y;
                    orientation[2] = q.z;
                    orientation[3] = q.w;
                }
            }
        }
        // Some runtimes expose one optical pinch as joints AND a controller select/trigger.
        // Latch its source until release, including through a hand-tracking dropout.
        for (int hand = 0; hand < 2; ++hand) {
            if (!selectPressed[hand] && !triggerPressed[hand]) {
                suppressPointerClick[hand] = false;
                continue;
            }
            if (out.handValid[hand]) {
                const auto& joints = out.joints[hand];
                float gapSquared = 0.f;
                for (int axis = 0; axis < 3; ++axis) {
                    const float delta = joints[XR_HAND_JOINT_THUMB_TIP_EXT * 3 + axis] -
                                        joints[XR_HAND_JOINT_INDEX_TIP_EXT * 3 + axis];
                    gapSquared += delta * delta;
                }
                if (gapSquared <= .055f * .055f) suppressPointerClick[hand] = true;
            }
            if (suppressPointerClick[hand]) continue;
            if (selectPressed[hand]) mask |= 2;
            if (triggerPressed[hand]) mask |= hand == 0 ? 64 : 2;
        }
        out.controls[5] = static_cast<float>(mask);
        if(samples%180==0)__android_log_print(ANDROID_LOG_INFO,"FourthwardXR","Input controllers=%d,%d hands=%d,%d",out.controls[0]>.5f,out.controls[1]>.5f,out.handValid[0],out.handValid[1]);
        return out;
    }
    void close(){
        for(auto tracker:trackers)if(tracker&&destroyTracker)destroyTracker(tracker);
        trackers={};
        for(auto s:aimSpaces)if(s)xrDestroySpace(s);
        aimSpaces={};
        if(actionSet)xrDestroyActionSet(actionSet);actionSet=XR_NULL_HANDLE;
    }
};
