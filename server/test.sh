#!/bin/bash

echo "🔐 Flussonic Auth Backend Test Script"
echo "======================================"
echo ""

BASE_URL="http://localhost:3000"

echo "1️⃣ Health Check"
curl -s "$BASE_URL/health" | jq
echo ""
echo ""

echo "2️⃣ List All Tokens"
curl -s "$BASE_URL/tokens" | jq
echo ""
echo ""

echo "3️⃣ Test Valid Token Authorization"
curl -s "$BASE_URL/auth?token=wLaPEFi23KFwI0&ip=192.168.1.100&name=test_channel" -v 2>&1 | grep -E "(< HTTP|< X-|user_id)"
echo ""
echo ""

echo "4️⃣ Test Invalid Token Authorization"
curl -s "$BASE_URL/auth?token=invalid_token&ip=192.168.1.100&name=test_channel" -v 2>&1 | grep -E "(< HTTP|error)"
echo ""
echo ""

echo "5️⃣ Add New Token"
curl -s -X POST "$BASE_URL/tokens" \
  -H "Content-Type: application/json" \
  -d '{
    "token": "test123",
    "userId": "user2",
    "maxSessions": 2,
    "duration": 1800,
    "allowedStreams": ["stream1", "stream2"]
  }' | jq
echo ""
echo ""

echo "6️⃣ List Tokens Again (should show 2)"
curl -s "$BASE_URL/tokens" | jq
echo ""
echo ""

echo "7️⃣ Test New Token"
curl -s "$BASE_URL/auth?token=test123&ip=10.0.0.1&name=stream1" | jq
echo ""
echo ""

echo "8️⃣ Delete Token"
curl -s -X DELETE "$BASE_URL/tokens/test123" | jq
echo ""
echo ""

echo "✅ Test Complete!"
