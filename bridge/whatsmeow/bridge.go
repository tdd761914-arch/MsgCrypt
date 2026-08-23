// Package whatsbridge exposes the text-only part of whatsmeow through gomobile.
// Each Manager owns one isolated WhatsApp account/session database.
package whatsbridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	_ "github.com/mattn/go-sqlite3"
	"google.golang.org/protobuf/proto"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

// Listener is implemented by Swift/Java. Events are compact JSON objects.
type Listener interface {
	OnEvent(eventJSON string)
}

type event struct {
	Type       string `json:"type"`
	State      string `json:"state,omitempty"`
	Detail     string `json:"detail,omitempty"`
	QR         string `json:"qr,omitempty"`
	ChatID     string `json:"chat_id,omitempty"`
	ChatTitle  string `json:"chat_title,omitempty"`
	MessageID  string `json:"message_id,omitempty"`
	Text       string `json:"text,omitempty"`
	Timestamp  int64  `json:"timestamp,omitempty"`
	Outgoing   bool   `json:"outgoing,omitempty"`
	Unread     int    `json:"unread,omitempty"`
	LastText   string `json:"last_text,omitempty"`
}

type chat struct {
	ID       string `json:"id"`
	Title    string `json:"title"`
	LastText string `json:"last_text"`
	LastAt   int64  `json:"last_at"`
	Unread   int    `json:"unread"`
}

// Manager is safe to call from UI and callback threads.
type Manager struct {
	mu        sync.RWMutex
	ctx       context.Context
	cancel    context.CancelFunc
	container *sqlstore.Container
	client    *whatsmeow.Client
	listener  Listener
	chats     map[string]chat
	closed    bool
}

// NewManager opens (or creates) one persistent WhatsMeow account.
func NewManager(root string, listener Listener) (*Manager, error) {
	if root == "" {
		return nil, errors.New("empty account root")
	}
	if err := os.MkdirAll(root, 0o700); err != nil {
		return nil, fmt.Errorf("create account root: %w", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	dsn := "file:" + filepath.Join(root, "whatsmeow.db") + "?_foreign_keys=on&_busy_timeout=5000"
	container, err := sqlstore.New(ctx, "sqlite3", dsn, nil)
	if err != nil {
		cancel()
		return nil, err
	}
	device, err := container.GetFirstDevice(ctx)
	if err != nil {
		cancel()
		_ = container.Close()
		return nil, err
	}
	m := &Manager{ctx: ctx, cancel: cancel, container: container, listener: listener, chats: make(map[string]chat)}
	m.client = whatsmeow.NewClient(device, nil)
	m.client.EnableAutoReconnect = true
	m.client.AddEventHandler(m.handleEvent)
	return m, nil
}

// Connect reconnects an existing account or starts QR pairing for a new one.
func (m *Manager) Connect() error {
	m.mu.RLock()
	if m.closed {
		m.mu.RUnlock()
		return errors.New("manager is closed")
	}
	client := m.client
	ctx := m.ctx
	m.mu.RUnlock()
	m.emit(event{Type: "auth", State: "connecting"})
	if client.Store.ID == nil {
		qr, err := client.GetQRChannel(ctx)
		if err != nil {
			return err
		}
		go func() {
			for item := range qr {
				switch item.Event {
				case whatsmeow.QRChannelEventCode:
					m.emit(event{Type: "qr", QR: item.Code})
				case "success":
					m.emit(event{Type: "auth", State: "connecting"})
				case "error", "timeout", "err-client-outdated", "err-unexpected-state":
					detail := item.Event
					if item.Error != nil { detail = item.Error.Error() }
					m.emit(event{Type: "error", Detail: detail})
				}
			}
		}()
	}
	return client.Connect()
}

// SendText sends exactly one carrier text. Media APIs are intentionally absent.
func (m *Manager) SendText(chatID, text string) (string, error) {
	if text == "" { return "", errors.New("empty text") }
	jid, err := types.ParseJID(chatID)
	if err != nil { return "", err }
	resp, err := m.client.SendMessage(m.ctx, jid, &waE2E.Message{Conversation: proto.String(text)})
	if err != nil { return "", err }
	return string(resp.ID), nil
}

// ListChats returns chats learned from history sync and live text messages.
func (m *Manager) ListChats() (string, error) {
	m.mu.RLock()
	items := make([]chat, 0, len(m.chats))
	for _, value := range m.chats { items = append(items, value) }
	m.mu.RUnlock()
	sort.Slice(items, func(i, j int) bool { return items[i].LastAt > items[j].LastAt })
	data, err := json.Marshal(items)
	return string(data), err
}

// LoadHistory is asynchronous in WhatsMeow: history arrives through HistorySync events.
func (m *Manager) LoadHistory(chatID string, limit int) error {
	if _, err := types.ParseJID(chatID); err != nil { return err }
	if limit < 1 || limit > 500 { return errors.New("history limit out of range") }
	return nil
}

func (m *Manager) Logout() error {
	if m.client == nil { return nil }
	return m.client.Logout(m.ctx)
}

func (m *Manager) Close() {
	m.mu.Lock()
	if m.closed { m.mu.Unlock(); return }
	m.closed = true
	m.mu.Unlock()
	m.cancel()
	if m.client != nil { m.client.Disconnect() }
	if m.container != nil { _ = m.container.Close() }
}

func (m *Manager) handleEvent(raw any) {
	switch value := raw.(type) {
	case *events.Connected:
		detail := m.client.Store.PushName
		if detail == "" && m.client.Store.ID != nil { detail = m.client.Store.ID.User }
		m.emit(event{Type: "auth", State: "ready", Detail: detail})
	case *events.LoggedOut:
		m.emit(event{Type: "auth", State: "logged_out", Detail: value.Reason.String()})
	case *events.Disconnected:
		m.emit(event{Type: "auth", State: "connecting"})
	case *events.Message:
		m.handleMessage(value, "")
	case *events.HistorySync:
		m.handleHistory(value)
	}
}

func (m *Manager) handleHistory(history *events.HistorySync) {
	if history == nil || history.Data == nil { return }
	for _, conversation := range history.Data.GetConversations() {
		jid, err := types.ParseJID(conversation.GetID())
		if err != nil { continue }
		title := firstNonEmpty(conversation.GetDisplayName(), conversation.GetName(), jid.User)
		m.upsertChat(chat{ID: jid.String(), Title: title, LastAt: int64(conversation.GetConversationTimestamp()), Unread: int(conversation.GetUnreadCount())})
		for _, item := range conversation.GetMessages() {
			parsed, err := m.client.ParseWebMessage(jid, item.GetMessage())
			if err == nil { m.handleMessage(parsed, title) }
		}
	}
}

func (m *Manager) handleMessage(message *events.Message, knownTitle string) {
	if message == nil || message.Message == nil { return }
	text := message.Message.GetConversation()
	if text == "" && message.Message.GetExtendedTextMessage() != nil {
		text = message.Message.GetExtendedTextMessage().GetText()
	}
	if text == "" { return } // text-only product: no captions/media fallbacks
	chatID := message.Info.Chat.String()
	title := knownTitle
	if title == "" {
		title = message.Info.PushName
		if contact, err := m.client.Store.Contacts.GetContact(m.ctx, message.Info.Chat); err == nil {
			title = firstNonEmpty(contact.FullName, contact.BusinessName, contact.PushName, title)
		}
		title = firstNonEmpty(title, message.Info.Chat.User)
	}
	timestamp := message.Info.Timestamp.Unix()
	m.upsertChat(chat{ID: chatID, Title: title, LastText: text, LastAt: timestamp})
	m.emit(event{Type: "text", ChatID: chatID, ChatTitle: title, MessageID: string(message.Info.ID),
		Text: text, Timestamp: timestamp, Outgoing: message.Info.IsFromMe})
}

func (m *Manager) upsertChat(next chat) {
	m.mu.Lock()
	old := m.chats[next.ID]
	if next.Title == "" { next.Title = old.Title }
	if next.LastText == "" { next.LastText = old.LastText }
	if next.LastAt == 0 { next.LastAt = old.LastAt }
	if next.Unread == 0 { next.Unread = old.Unread }
	m.chats[next.ID] = next
	m.mu.Unlock()
	m.emit(event{Type: "chat", ChatID: next.ID, ChatTitle: next.Title, LastText: next.LastText,
		Timestamp: next.LastAt, Unread: next.Unread})
}

func (m *Manager) emit(value event) {
	m.mu.RLock()
	listener := m.listener
	closed := m.closed
	m.mu.RUnlock()
	if listener == nil || closed { return }
	data, err := json.Marshal(value)
	if err != nil { return }
	listener.OnEvent(string(data))
}

func firstNonEmpty(values ...string) string {
	for _, value := range values { if value != "" { return value } }
	return ""
}

// Keep time imported on platforms where gomobile's linker strips optional paths.
var _ = time.Second

