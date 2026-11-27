package ha

import (
	"context"
	"crypto/hmac"
	"crypto/sha1"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/redis/go-redis/v9"
)

type SignalEnvelope struct {
	Type           string          `json:"type"`
	SessionID      string          `json:"session_id"`
	TargetDeviceID string          `json:"target_device_id"`
	SenderDeviceID string          `json:"sender_device_id"`
	Payload        json.RawMessage `json:"payload"`
}

type NodeClient struct {
	DeviceID string
	UserID   string
	Conn     *websocket.Conn
	SendChan chan []byte
}

type TurnCredentials struct {
	URLs       []string `json:"urls"`
	Username   string   `json:"username"`
	Credential string   `json:"credential"`
	TTLSeconds int      `json:"ttl_seconds"`
}

type HighAvailabilityHub struct {
	nodeID     string
	rdb        *redis.Client
	turnSecret string
	turnURLs   []string
	localPeers sync.Map
}

func NewHighAvailabilityHub(nodeID string, rdb *redis.Client, turnSecret string, turnURLs []string) *HighAvailabilityHub {
	return &HighAvailabilityHub{
		nodeID:     nodeID,
		rdb:        rdb,
		turnSecret: turnSecret,
		turnURLs:   turnURLs,
	}
}

func (h *HighAvailabilityHub) StartClusterSubscriber(ctx context.Context) {
	channel := "signal:node:" + h.nodeID
	pubsub := h.rdb.Subscribe(ctx, channel)

	go func() {
		defer pubsub.Close()
		ch := pubsub.Channel()
		for {
			select {
			case <-ctx.Done():
				return
			case msg, ok := <-ch:
				if !ok {
					return
				}
				h.deliverToLocalPeer([]byte(msg.Payload))
			}
		}
	}()
}

func (h *HighAvailabilityHub) RegisterPeer(ctx context.Context, client *NodeClient) error {
	h.localPeers.Store(client.DeviceID, client)
	return h.rdb.Set(ctx, "peer:node:"+client.DeviceID, h.nodeID, 45*time.Second).Err()
}

func (h *HighAvailabilityHub) UnregisterPeer(ctx context.Context, deviceID string) {
	h.localPeers.Delete(deviceID)
	_ = h.rdb.Del(ctx, "peer:node:"+deviceID).Err()
}

func (h *HighAvailabilityHub) RouteAuthenticatedSignal(ctx context.Context, sender *NodeClient, raw []byte) error {
	var env SignalEnvelope
	if err := json.Unmarshal(raw, &env); err != nil {
		return fmt.Errorf("invalid signaling json: %w", err)
	}
	env.SenderDeviceID = sender.DeviceID

	if env.Type == "PING" {
		_ = h.rdb.Expire(ctx, "peer:node:"+sender.DeviceID, 45*time.Second).Err()
		return nil
	}
	if env.TargetDeviceID == "" {
		return fmt.Errorf("missing target_device_id")
	}

	aclKey := fmt.Sprintf("acl:pair:%s:%s", sender.DeviceID, env.TargetDeviceID)
	allowed, err := h.rdb.Get(ctx, aclKey).Result()
	if err != nil || allowed != "1" {
		return fmt.Errorf("unauthorized target device %s", env.TargetDeviceID)
	}

	cleanBytes, err := json.Marshal(env)
	if err != nil {
		return err
	}
	if h.deliverToLocalPeer(cleanBytes) {
		return nil
	}

	targetNode, err := h.rdb.Get(ctx, "peer:node:"+env.TargetDeviceID).Result()
	if err != nil || targetNode == "" {
		return fmt.Errorf("target device %s is offline", env.TargetDeviceID)
	}
	return h.rdb.Publish(ctx, "signal:node:"+targetNode, string(cleanBytes)).Err()
}

func (h *HighAvailabilityHub) deliverToLocalPeer(raw []byte) bool {
	var env SignalEnvelope
	if json.Unmarshal(raw, &env) != nil {
		return false
	}
	val, ok := h.localPeers.Load(env.TargetDeviceID)
	if !ok {
		return false
	}
	peer := val.(*NodeClient)
	select {
	case peer.SendChan <- raw:
		return true
	default:
		slog.Warn("Drop frame due to saturated peer send channel", "device_id", peer.DeviceID)
		return false
	}
}

func (h *HighAvailabilityHub) IssueEphemeralTurnCredentials(sessionID string, ttlSeconds int) TurnCredentials {
	if ttlSeconds <= 0 {
		ttlSeconds = 120
	}
	expiryUnix := time.Now().UTC().Unix() + int64(ttlSeconds)
	username := fmt.Sprintf("%d:%s", expiryUnix, sessionID)

	mac := hmac.New(sha1.New, []byte(h.turnSecret))
	mac.Write([]byte(username))
	credential := base64.StdEncoding.EncodeToString(mac.Sum(nil))

	return TurnCredentials{
		URLs:       h.turnURLs,
		Username:   username,
		Credential: credential,
		TTLSeconds: ttlSeconds,
	}
}
