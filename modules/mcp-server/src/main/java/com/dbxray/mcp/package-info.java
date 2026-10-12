/**
 * スキーマのスナップショットをAIから検索できるようにするMCPサーバー<br>
 * 依存の向きは{@code tool → catalog ← snapshot}・{@code catalog ← insight}。
 * 検索・リレーションのたどりは{@code catalog}に置き、MCPのSDKにもJSONにも依存させない
 */
package com.dbxray.mcp;
