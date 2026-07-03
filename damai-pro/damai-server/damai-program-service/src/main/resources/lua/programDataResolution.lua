local ticket_category_list = cjson.decode(ARGV[1])
local del_seat_list = cjson.decode(ARGV[2])
local add_seat_data_list = cjson.decode(ARGV[3])

local actual_count_by_category = {}
local removed_seat_by_category = {}

for index, seat in pairs(del_seat_list) do
    local seat_hash_key_del = seat.seatHashKeyDel
    local ticket_category_id = tostring(seat.ticketCategoryId)
    local seat_id_list = seat.seatIdList
    if not actual_count_by_category[ticket_category_id] then
        actual_count_by_category[ticket_category_id] = 0
    end
    if not removed_seat_by_category[ticket_category_id] then
        removed_seat_by_category[ticket_category_id] = {}
    end
    for _, seat_id in ipairs(seat_id_list) do
        local seat_id_str = tostring(seat_id)
        if redis.call('HEXISTS', seat_hash_key_del, seat_id_str) == 1 then
            redis.call('HDEL', seat_hash_key_del, seat_id_str)
            actual_count_by_category[ticket_category_id] = actual_count_by_category[ticket_category_id] + 1
            removed_seat_by_category[ticket_category_id][seat_id_str] = true
        end
    end
end
for index,increase_data in ipairs(ticket_category_list) do
    local program_ticket_remain_number_hash_key = increase_data.programTicketRemainNumberHashKey
    local ticket_category_id = tostring(increase_data.ticketCategoryId)
    local requested_count = tonumber(increase_data.count)
    local actual_count = actual_count_by_category[ticket_category_id] or 0
    if requested_count < 0 then
        redis.call('HINCRBY',program_ticket_remain_number_hash_key,ticket_category_id,-actual_count)
    else
        redis.call('HINCRBY',program_ticket_remain_number_hash_key,ticket_category_id,actual_count)
    end
end
for index, seat in pairs(add_seat_data_list) do
    local seat_hash_key_add = seat.seatHashKeyAdd
    local ticket_category_id = tostring(seat.ticketCategoryId)
    local seat_data_list = seat.seatDataList
    local filtered_seat_data_list = {}
    for i = 1, #seat_data_list, 2 do
        local seat_id = tostring(seat_data_list[i])
        if removed_seat_by_category[ticket_category_id] and removed_seat_by_category[ticket_category_id][seat_id] then
            table.insert(filtered_seat_data_list, seat_id)
            table.insert(filtered_seat_data_list, seat_data_list[i + 1])
        end
    end
    if #filtered_seat_data_list > 0 then
        redis.call('HMSET',seat_hash_key_add,unpack(filtered_seat_data_list))
    end
end
