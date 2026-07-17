//
// Created by Constantin on 24.01.2018.
//
#include "H26XParser.h"
#include <android/log.h>
#include <endian.h>
#include <chrono>
#include <cstring>
#include <thread>

H26XParser::H26XParser(NALU_DATA_CALLBACK onNewNALU)
    : onNewNALU(std::move(onNewNALU)),
      mDecodeRTP(std::bind(
          &H26XParser::onNewNaluDataExtracted,
          this,
          std::placeholders::_1,
          std::placeholders::_2,
          std::placeholders::_3))
{
}

void H26XParser::reset()
{
    mDecodeRTP.reset();
    nParsedNALUs               = 0;
    nParsedKonfigurationFrames = 0;
}

void H26XParser::parse_rtp_stream(const uint8_t* rtp_data, const size_t data_length)
{
    // An empty/short buffered packet reaches here as (nullptr, 0) — e.g. std::vector::data() on an
    // empty vector — and the RTPPacket ctor dereferences rtp_data (reads header.padding) → SIGSEGV.
    // Reject anything smaller than a full RTP header before touching the pointer.
    if (rtp_data == nullptr || data_length < sizeof(rtp_header_t))
    {
        return;
    }
    const RTP::RTPPacket rtpPacket(rtp_data, data_length);
    if (rtpPacket.header.payload == RTP_PAYLOAD_TYPE_H264)
    {
        IS_H265 = false;
        mDecodeRTP.parseRTPH264toNALU(rtp_data, data_length);
    }
    else if (rtpPacket.header.payload == RTP_PAYLOAD_TYPE_H265)
    {
        IS_H265 = true;
        mDecodeRTP.parseRTPH265toNALU(rtp_data, data_length);
    }
}

void H26XParser::onNewNaluDataExtracted(
    const std::chrono::steady_clock::time_point creation_time, const uint8_t* nalu_data, const int nalu_data_size)
{
    // A torn/short NALU (lossy dongle stream) would make NALU's ctor read its start-code prefix
    // out of bounds and feed sub-minimal data to the decoder. Drop it here — the display just
    // waits for the next complete NALU/keyframe instead of the app aborting or corrupting memory.
    if (nalu_data == nullptr || nalu_data_size < (int) NALU::getMinimumNaluSize(IS_H265))
    {
        return;
    }
    NALU nalu(nalu_data, nalu_data_size, IS_H265, creation_time);
    newNaluExtracted(nalu);
}

void H26XParser::newNaluExtracted(const NALU& nalu)
{
    if (onNewNALU != nullptr)
    {
        onNewNALU(nalu);
    }
    nParsedNALUs++;
    const bool sps_or_pps = nalu.isSPS() || nalu.isPPS();
    if (sps_or_pps)
    {
        nParsedKonfigurationFrames++;
    }
}
