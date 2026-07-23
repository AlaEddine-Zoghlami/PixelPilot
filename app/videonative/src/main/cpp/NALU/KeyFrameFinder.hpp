//
// Created by geier on 07/02/2020.
//

#ifndef FPVUE_KEYFRAMEFINDER_HPP
#define FPVUE_KEYFRAMEFINDER_HPP

#include <memory>
#include <vector>
#include "../helper/AndroidLogger.hpp"
#include "NALU.hpp"

// Takes a continuous stream of NALUs and save SPS / PPS data
// For later use
class KeyFrameFinder
{
  private:
    std::unique_ptr<NALUBuffer> SPS = nullptr;
    std::unique_ptr<NALUBuffer> PPS = nullptr;
    // VPS are only used in H265
    std::unique_ptr<NALUBuffer> VPS = nullptr;

  public:
    bool saveIfKeyFrame(const NALU& nalu)
    {
        if (nalu.getSize() <= 0) return false;
        if (nalu.isSPS())
        {
            SPS = std::make_unique<NALUBuffer>(nalu);
            // MLOGD<<"SPS found";
            // MLOGD<<nalu.get_sps_as_string().c_str();
            return true;
        }
        else if (nalu.isPPS())
        {
            PPS = std::make_unique<NALUBuffer>(nalu);
            // MLOGD<<"PPS found";
            return true;
        }
        else if (nalu.IS_H265_PACKET && nalu.isVPS())
        {
            VPS = std::make_unique<NALUBuffer>(nalu);
            // MLOGD<<"VPS found";
            return true;
        }
        // qDebug()<<"not a keyframe"<<(int)nalu.getDataWithoutPrefix()[0];
        return false;
    }

    // H264 needs sps and pps
    // H265 needs sps,pps and vps
    bool allKeyFramesAvailable(const bool IS_H265 = false)
    {
        if (IS_H265)
        {
            return SPS != nullptr && PPS != nullptr && VPS != nullptr;
        }
        return SPS != nullptr && PPS != nullptr;
    }

    // SPS
    const NALU& getCSD0() const
    {
        assert(SPS);
        return SPS->get_nal();
    }

    const NALU& getCSD1() const
    {
        assert(PPS);
        return PPS->get_nal();
    }

    const NALU& getVPS() const
    {
        assert(VPS);
        return VPS->get_nal();
    }

    static void appendNaluData(std::vector<uint8_t>& buff, const NALU& nalu)
    {
        // APPEND, not prepend. This used to insert at buff.begin() on every call, so three
        // calls in the caller's intended order (VPS, SPS, PPS) actually produced the csd-0
        // buffer in REVERSE (PPS, SPS, VPS). The AOSP software HEVC decoder tolerates
        // out-of-order parameter sets (buffers all NALs, resolves vps_id/sps_id references
        // after a full parse), which is why this went unnoticed — but MTK's HW decoder
        // (c2.mtk.hevc.decoder) appears to parse sequentially/statefully and needs canonical
        // VPS->SPS->PPS order; fed PPS first with no SPS/VPS context yet, it fell back to a
        // default resolution (176x144) and then hard-failed (C2MtkVdec FatalError(DecodeError)).
        buff.insert(buff.end(), nalu.getData(), nalu.getData() + nalu.getSize());
    }

    void reset()
    {
        SPS = nullptr;
        PPS = nullptr;
        VPS = nullptr;
    }
};

#endif  // FPVUE_KEYFRAMEFINDER_HPP
