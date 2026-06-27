import struct
import os

base_path = r'C:\Users\jinyu\Desktop\540423496360\2026-06-08\1.rtcm3'
rover_path = r'C:\Users\jinyu\Desktop\540423494727\2026-06-08\1.rtcm3'

def parse_rtcm_messages(filepath):
    msgs = {}
    with open(filepath, 'rb') as f:
        data = f.read()
    i = 0
    while i < len(data) - 3:
        if data[i] == 0xD3:
            length = (data[i+1] << 8) | data[i+2]
            length &= 0x03FF
            if i + 3 + length + 3 <= len(data):
                msg = data[i:i+3+length+3]
                msg_type = (msg[3] << 4) | (msg[4] >> 4)
                if msg_type not in msgs:
                    msgs[msg_type] = {'count': 0, 'positions': []}
                msgs[msg_type]['count'] += 1
                if msg_type == 1005:
                    pos = msg[3+3:]
                    ecef_x_raw = ((pos[0] & 0xFF) << 30) | ((pos[1] & 0xFF) << 22) | ((pos[2] & 0xFF) << 14) | ((pos[3] & 0xFF) << 6) | ((pos[4] & 0xFC) >> 2)
                    ecef_y_raw = (((pos[4] & 0x03) << 36) | ((pos[5] & 0xFF) << 28) | ((pos[6] & 0xFF) << 20) | ((pos[7] & 0xFF) << 12) | ((pos[8] & 0xFF) << 4) | ((pos[9] & 0xF0) >> 4))
                    ecef_z_raw = (((pos[9] & 0x0F) << 34) | ((pos[10] & 0xFF) << 26) | ((pos[11] & 0xFF) << 18) | ((pos[12] & 0xFF) << 10) | ((pos[13] & 0xFF) << 2) | ((pos[14] & 0xC0) >> 6))
                    x = ecef_x_raw * 0.0001
                    y = ecef_y_raw * 0.0001
                    z = ecef_z_raw * 0.0001
                    msgs[msg_type]['positions'].append((x, y, z))
                elif msg_type == 1006:
                    pos = msg[3+3:]
                    ecef_x_raw = ((pos[0] & 0xFF) << 30) | ((pos[1] & 0xFF) << 22) | ((pos[2] & 0xFF) << 14) | ((pos[3] & 0xFF) << 6) | ((pos[4] & 0xFC) >> 2)
                    ecef_y_raw = (((pos[4] & 0x03) << 36) | ((pos[5] & 0xFF) << 28) | ((pos[6] & 0xFF) << 20) | ((pos[7] & 0xFF) << 12) | ((pos[8] & 0xFF) << 4) | ((pos[9] & 0xF0) >> 4))
                    ecef_z_raw = (((pos[9] & 0x0F) << 34) | ((pos[10] & 0xFF) << 26) | ((pos[11] & 0xFF) << 18) | ((pos[12] & 0xFF) << 10) | ((pos[13] & 0xFF) << 2) | ((pos[14] & 0xC0) >> 6))
                    x = ecef_x_raw * 0.0001
                    y = ecef_y_raw * 0.0001
                    z = ecef_z_raw * 0.0001
                    msgs[msg_type]['positions'].append((x, y, z))
                i += 3 + length + 3
            else:
                i += 1
        else:
            i += 1
    return msgs, len(data)

print("=== BASE STATION RTCM ===")
b_msgs, b_size = parse_rtcm_messages(base_path)
print("File size: {} bytes".format(b_size))
for t in sorted(b_msgs.keys()):
    info = b_msgs[t]
    if t in [1005, 1006]:
        uniq = set(info['positions'])
        print("Type {}: {} messages".format(t, info['count']))
        for p in sorted(uniq):
            print("  X={:.4f} Y={:.4f} Z={:.4f}".format(p[0], p[1], p[2]))
    else:
        print("Type {}: {} messages".format(t, info['count']))

print()
print("=== ROVER RTCM ===")
r_msgs, r_size = parse_rtcm_messages(rover_path)
print("File size: {} bytes".format(r_size))
for t in sorted(r_msgs.keys()):
    info = r_msgs[t]
    if t in [1005, 1006]:
        uniq = set(info['positions'])
        print("Type {}: {} messages".format(t, info['count']))
        for p in sorted(uniq):
            print("  X={:.4f} Y={:.4f} Z={:.4f}".format(p[0], p[1], p[2]))
    else:
        print("Type {}: {} messages".format(t, info['count']))