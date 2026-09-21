package com.blive.tv.data.update.api

import retrofit2.http.GET
import retrofit2.http.Path

interface GithubReleaseApi {
    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun getLatestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): GithubReleaseDto
}

interface GiteeReleaseApi {
    @GET("api/v5/repos/{owner}/{repo}/releases/latest")
    suspend fun getLatestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): GiteeReleaseDto
}
