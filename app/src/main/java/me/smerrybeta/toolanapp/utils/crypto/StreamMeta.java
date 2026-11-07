package me.smerrybeta.toolanapp.utils.crypto;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A1L 流式传输的首行元数据（也用作加密上传的 meta 段），与服务端
 * security.StreamMeta 的 JSON 字段一一对应。
 */
public class StreamMeta {
    public String name;
    public String mime;
    public long size;   // 已知时用于完整性校验；流式打包（zip）可为 0
    public String folder; // 仅上传使用：目标子文件夹

    public StreamMeta() {
    }

    public StreamMeta(String name, String mime, long size) {
        this.name = name;
        this.mime = mime;
        this.size = size;
    }

    public String toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", name == null ? "" : name);
        if (mime != null && !mime.isEmpty()) {
            o.put("mime", mime);
        }
        o.put("size", size);
        if (folder != null && !folder.isEmpty()) {
            o.put("folder", folder);
        }
        return o.toString();
    }

    public static StreamMeta fromJson(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        StreamMeta m = new StreamMeta();
        m.name = o.optString("name", "");
        m.mime = o.optString("mime", "");
        m.size = o.optLong("size", 0);
        m.folder = o.optString("folder", "");
        return m;
    }
}
