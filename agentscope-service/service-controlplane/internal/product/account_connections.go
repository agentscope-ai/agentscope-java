// Copyright 2024-2026 the original author or authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
package product

import (
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

// Personal settings expose only the caller's bindings/subscriptions, including
// bindings they need to revoke after losing namespace membership. No credentials
// or Issue content are returned by this account-level projection.
func (s *Server) myChannelConnections(c *gin.Context) {
	rows, err := s.db.Pool.Query(c.Request.Context(), `SELECT i.channel_id,c.type,i.account_id,i.sender_id FROM channel_identities i JOIN channels c USING(channel_id) WHERE i.user_id=$1 ORDER BY i.channel_id,i.sender_id`, currentUserID(c))
	if err != nil {
		writeErr(c, 500, "Unable to load IM identities")
		return
	}
	identities := []gin.H{}
	for rows.Next() {
		var channel, platform, account, sender string
		if err = rows.Scan(&channel, &platform, &account, &sender); err != nil {
			rows.Close()
			writeErr(c, 500, "Unable to load IM identities")
			return
		}
		identities = append(identities, gin.H{"channelId": channel, "platform": platform, "accountId": account, "senderId": sender})
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		writeErr(c, 500, "Unable to load IM identities")
		return
	}
	rows, err = s.db.Pool.Query(c.Request.Context(), `SELECT l.id,l.channel_id,c.type,l.issue_id FROM channel_work_links l JOIN channels c USING(channel_id) WHERE l.user_id=$1 AND l.active=true ORDER BY l.channel_id,l.id LIMIT 500`, currentUserID(c))
	if err != nil {
		writeErr(c, 500, "Unable to load notification subscriptions")
		return
	}
	defer rows.Close()
	links := []gin.H{}
	for rows.Next() {
		var id, issue uuid.UUID
		var channel, platform string
		if err = rows.Scan(&id, &channel, &platform, &issue); err != nil {
			writeErr(c, 500, "Unable to load notification subscriptions")
			return
		}
		links = append(links, gin.H{"id": id, "channelId": channel, "platform": platform, "issueId": issue})
	}
	if rows.Err() != nil {
		writeErr(c, 500, "Unable to load notification subscriptions")
		return
	}
	c.JSON(200, gin.H{"identities": identities, "subscriptions": links})
}
func (s *Server) removeMyChannelIdentity(c *gin.Context) {
	var in struct {
		ChannelID string `json:"channelId"`
		AccountID string `json:"accountId"`
		SenderID  string `json:"senderId"`
	}
	if c.ShouldBindJSON(&in) != nil || in.ChannelID == "" || in.SenderID == "" {
		writeErr(c, 400, "Channel and sender are required")
		return
	}
	_, err := s.db.Pool.Exec(c.Request.Context(), `DELETE FROM channel_identities WHERE channel_id=$1 AND account_id=$2 AND sender_id=$3 AND user_id=$4`, in.ChannelID, in.AccountID, in.SenderID, currentUserID(c))
	if err != nil {
		writeErr(c, 500, "Unable to unlink IM identity")
		return
	}
	c.Status(204)
}
func (s *Server) unsubscribeMyChannel(c *gin.Context) {
	id, err := uuid.Parse(c.Param("subscriptionId"))
	if err != nil {
		writeErr(c, 400, "Invalid subscription")
		return
	}
	tag, err := s.db.Pool.Exec(c.Request.Context(), `UPDATE channel_work_links SET active=false WHERE id=$1 AND user_id=$2`, id, currentUserID(c))
	if err != nil {
		writeErr(c, 500, "Unable to unsubscribe")
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, 404, "Subscription not found")
		return
	}
	c.Status(204)
}
