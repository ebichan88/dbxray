/**
 * 層をまたいで失敗の分類を伝える例外のパッケージです<br>
 * レイヤー（presentation・application・domain・infrastructure）の外に置き、どの層・パッケージからも依存してよい。
 * このパッケージ自身はJDK以外に依存しない。置いてよいのは、失敗の分類を伝える例外だけとする（{@code shared}の直下や、例外以外の共通部品は置かない）
 */
package com.dbxray.shared.exception;
