package moe.fuqiuluo.http.action.helper

import android.util.LruCache
import com.tencent.biz.map.trpcprotocol.LbsSendInfo
import com.tencent.mobileqq.app.QQAppInterface
import com.tencent.proto.lbsshare.LBSShare
import com.tencent.qphone.base.remote.ToServiceMsg
import com.tencent.qqnt.kernel.nativeinterface.MsgConstant
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import moe.fuqiuluo.http.action.helper.msg.ParamsIllegalException
import moe.fuqiuluo.xposed.helper.PacketHandler
import moe.fuqiuluo.xposed.tools.slice
import mqq.app.MobileQQ
import kotlin.math.roundToInt

internal object LocationHelper {
    private val LruCachePrivate = LruCache<String, String>(10)

    init {
        PacketHandler.register("LbsShareSvr.location") {
            val resp = LBSShare.LocationResp()
            resp.mergeFrom(it.slice(4))
            val location = resp.mylbs
            val lat = location.lat.get()
            val lon = location.lng.get()
            val address = location.addr.get()
            LruCachePrivate.put("$lat|$lon", address)
        }
    }

    suspend fun sendShareLocation(chatType: Int, peerId: Long, lat: Double, lon: Double) {
        val req = LbsSendInfo.SendMessageReq()
        req.uint64_peer_account.set(peerId)
        when (chatType) {
            MsgConstant.KCHATTYPEGROUP -> req.enum_relation_type.set(1)
            MsgConstant.KCHATTYPEC2C -> req.enum_relation_type.set(0)
            else -> error("Not supported chat type: $chatType")
        }
        req.str_name.set("位置分享")
        req.str_address.set(getLocationWithLonLat(lat, lon))
        req.str_lat.set(lat.toString())
        req.str_lng.set(lon.toString())
        val app = MobileQQ.getMobileQQ().waitAppRuntime() as QQAppInterface
        val toServiceMsg = ToServiceMsg("mobileqq.service", app.currentAccountUin, "trpc.qq_lbs.qq_lbs_ark.LocationArk.SsoSendMessage")
        toServiceMsg.putWupBuffer(req.toByteArray())
        toServiceMsg.addAttribute("req_pb_protocol_flag", true)
        app.sendToService(toServiceMsg)
    }

    suspend fun getLocationWithLonLat(lat: Double, lon: Double): String {
        if (lat > 90 || lat < 0) {
            throw ParamsIllegalException("纬度大小错误")
        }
        if (lon > 180 || lon < 0) {
            throw ParamsIllegalException("经度大小错误")
        }

        val latO = (lat * 1000000).roundToInt()
        val lngO = (lon * 1000000).roundToInt()
        val cacheKey = "$latO|$lngO"

        LruCachePrivate[cacheKey]?.let {
            return it
        }

        val req = LBSShare.LocationReq()
        req.lat.set(latO)
        req.lng.set(lngO)
        req.coordinate.set(1)
        req.keyword.set("")
        req.category.set("")
        req.page.set(0)
        req.count.set(20)
        req.requireMyLbs.set(1)
        req.imei.set("")
        val app = MobileQQ.getMobileQQ().waitAppRuntime() as QQAppInterface
        val toServiceMsg = ToServiceMsg("mobileqq.service", app.currentAccountUin, "LbsShareSvr.location")
        toServiceMsg.putWupBuffer(req.toByteArray())
        toServiceMsg.addAttribute("req_pb_protocol_flag", true)
        app.sendToService(toServiceMsg)

        return withTimeoutOrNull(10000) {
            var text: String? = null
            while (text == null) {
                delay(100)
                LruCachePrivate[cacheKey]?.let { text = it }
            }
            return@withTimeoutOrNull text
        } ?: error("unable to fetch location address")
    }
}