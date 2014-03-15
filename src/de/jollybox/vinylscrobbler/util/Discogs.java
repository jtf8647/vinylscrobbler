/**
 * Vinyl Scrobbler app by JollyBOX.de
 *
 * Copyright (c) 2011	Thomas Jollans
 * 
 * Refer to the file COPYING for copying permissions.
 */

package de.jollybox.vinylscrobbler.util;

import org.json.JSONException;
import org.json.JSONObject;
import org.scribe.builder.ServiceBuilder;
import org.scribe.model.OAuthRequest;
import org.scribe.model.Token;
import org.scribe.model.Verb;
import org.scribe.oauth.OAuthService;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.widget.Toast;
import de.jollybox.vinylscrobbler.R;

public class Discogs extends ContextWrapper {
	private final String API_KEY;
	private final String API_SECRET;

	private SharedPreferences mPrefs;
	private final static String PREFS_FILE_NAME = "de.jollybox.vinylscrobbler.Discogs";

	private OAuthService mOAuthService;
	private String mAccessToken;
	private String mAccessSecret;
	private String mUserName;
	private int mCollectionSize;
	private int mCollectionLast;
	private boolean mCacheCollection;
	private boolean mAutoadd;
	
	private ResultWaiter mWaiter;

	public Discogs(Context context) {
		super(context);
		Resources res = context.getResources();

		API_KEY = res.getString(R.string.discogs_api_key);
		API_SECRET = res.getString(R.string.discogs_api_secret);

		mPrefs = getSharedPreferences(PREFS_FILE_NAME, MODE_PRIVATE);
		mAccessToken = mPrefs.getString("access_token", null);
		mAccessSecret = mPrefs.getString("access_secret", null);
		mUserName = mPrefs.getString("user_name", null);
		mCollectionSize = mPrefs.getInt("collection_size", 0);
		mCollectionLast = mPrefs.getInt("collection_last", 0);
		mAutoadd = mPrefs.getBoolean("autoadd", false);
		mCacheCollection = mPrefs.getBoolean("cache_collection", true);
		
		mOAuthService = new ServiceBuilder().provider(DiscogsApi.class)
				.apiKey(API_KEY).apiSecret(API_SECRET)
				.callback("oauth://discogs").build();

	}
	
	public String getUser() {
		return mUserName;
	}
	
	//only add releases that are not added to discogs collection yet
	public void addRelease(final int id) {
		//first check if the user has the current release in his collection, do an info query
		//TODO check if instances/1 is a given when a release is present in a collection
		final String query_string = "/users/" + getUser() + "/collection/folders/0/releases/"+id+"/instances/1";
		DiscogsQuery lookupquery = new DiscogsQuery.WithAlertDialog(this, false, this) {
			@Override
			protected void onResult(JSONObject result) {
				try {
					//check if we get a "not found" error, release is missing from discogs collection
					if (result.has("message")) {
						if(result.getString("message").contains("not found")) {
							//add release to discogs
							final String query_string = "/users/" + getUser() + "/collection/folders/1/releases/"+id;
							DiscogsQuery addquery = new DiscogsQuery.WithAlertDialog(Discogs.this, false, Discogs.this) {
								@Override
								protected void onPreExecute() {
									//set the HTTP method to POST
									mHttpMethod = Verb.POST;
									super.onPreExecute();
								};
								@Override
								protected void onResult(JSONObject result) {
										//check if we correctly added the release
										if (result.has("resource_url")) {
											//correctly added release to the discogs collection
											Toast.makeText(Discogs.this, R.string.discogs_add_success, Toast.LENGTH_SHORT).show();
										} else {
											errorMessage("Could not add release to the discogs collection");
										}
								}
							};
							addquery.hideProgress();
							addquery.execute(query_string);
							return;
						} else if(result.getString("message").contains("authenticate")) {
							//the current discogs token is invalid, clear discogs session
							forgetSession();
							removeFromCache(query_string);
							errorMessage(res.getString(R.string.discogs_nologin));
							return;
						}
					}
					// if we don't get a not found error, assume the release is already added to the collection
					Toast.makeText(Discogs.this, R.string.discogs_already_added, Toast.LENGTH_SHORT).show();
				} catch (JSONException json_exc) {
					errorMessage("Cannot comprehend data");
				}
			}
		};
		lookupquery.hideProgress();
		lookupquery.execute(query_string);
		
	}
	
	public void onCollectionChanged(final ResultWaiter waiter) {
		final String query_string = "/users/" + getUser() + "/collection/folders/0/releases?per_page=1&page=1&sort=added&sort_order=desc";
		DiscogsQuery collectionstatquery = new DiscogsQuery(this, false, this) {
			@Override
			protected void onResult(JSONObject result) {
				try {
					//check if we get an authentication error (key remotely revoked)
					if (result.has("message")) {
						if(result.getString("message").contains("authenticate")) {
							//the current discogs token is invalid, clear discogs session and remove the query from the cache so after login we get the correct results
							forgetSession();
							removeFromCache(query_string);
							errorMessage(res.getString(R.string.discogs_nologin));
							return;
						}
					}
					int collectionSize = result.getJSONObject("pagination").getInt("items");
					if (collectionSize != 0) {
						int lastAddition = result.getJSONArray("releases").getJSONObject(0).getInt("id");
						//see if the remote collection has changed from the last fetch (+sanity check for local database)
						if(mCollectionSize != collectionSize || mCollectionLast != lastAddition || VinylDatabase.getInstance(mContext).getCollectionSize() != mCollectionSize) {
							//already store new values, but needs to be committed by saveCollectionState() after db updates
							mCollectionLast = lastAddition;
							mCollectionSize = collectionSize;
							waiter.onResult(true);
						} else {
							waiter.onResult(false);
						}
					}
					
				} catch (JSONException json_exc) {
					errorMessage("Cannot comprehend data");
				}
			}

			@Override
			protected void errorMessage(String message) {
				// Since this is a background query, ignore
			}
		};
		collectionstatquery.hideProgress();
		collectionstatquery.execute(query_string);
	}
	
	//only call this after writing 
	public void saveCollectionState() {
		SharedPreferences.Editor prefEdit = mPrefs.edit();
		prefEdit.putInt("collection_size", mCollectionSize);
		prefEdit.putInt("collection_last", mCollectionLast);
		prefEdit.commit();
	}

	public OAuthService getOAuthService() {
		return mOAuthService;
	}
	
	public Token getOAuthToken() {
		if (mAccessSecret != null && mAccessToken != null) {
			return new Token(mAccessToken,mAccessSecret);
		}
		return null;
	}
	
	public OAuthRequest signRequest(OAuthRequest request) {
		Token authToken = getOAuthToken();
		if(authToken != null) {
			mOAuthService.signRequest(getOAuthToken(), request);
		}
		return request;
	}
	
	public void setWaiter(ResultWaiter waiter) {
		mWaiter = waiter;
	}

	public void setSession(String accessToken, String accessSecret) {
		mAccessToken = accessToken;
		mAccessSecret = accessSecret;
		String query_string = "/oauth/identity";
		DiscogsQuery q = new DiscogsQuery(this, false, this) {
			@Override
			protected void onResult(JSONObject result) {
				String username;
				try {
					username = result.getString("username");
				} catch (Exception exc) {
					errorMessage(res.getString(R.string.error_invalid_data));
					return;
				}
				if (username != null) {
					//only save the credentials when a username has been correctly parsed
					mUserName = username;
					saveSession();
					if(mWaiter != null) {
						mWaiter.onResult(null);
					}
				}
			}

			@Override
			protected void errorMessage(String message) {
				// TODO Auto-generated method stub
				
			}
		};
		q.hideProgress();
		q.execute(query_string);
	}

	public void forgetSession() {
		mAccessToken = null;
		mAccessSecret = null;
		mUserName = null;
		saveSession();
	}

	private void saveSession() {
		SharedPreferences.Editor prefEdit = mPrefs.edit();

		if (mAccessToken != null) {
			prefEdit.putString("access_token", mAccessToken);
		} else {
			prefEdit.remove("access_token");
		}

		if (mAccessSecret != null) {
			prefEdit.putString("access_secret", mAccessSecret);
		} else {
			prefEdit.remove("access_secret");
		}
		
		if (mUserName != null) {
			prefEdit.putString("user_name", mUserName);
		} else {
			prefEdit.remove("user_name");
		}
		
		prefEdit.commit();
	}
	
	public boolean isAutoadd() {
		return mAutoadd;
	}

	public void setAutoadd(boolean mAutoadd) {
		this.mAutoadd = mAutoadd;
		mPrefs.edit().putBoolean("autoadd", mAutoadd).commit();
	}

	public interface ResultWaiter {
		public void onResult(Boolean result);
	}

	public void setCacheCollection(boolean cache) {
		this.mCacheCollection = cache;
		mCollectionLast = 0;
		mCollectionSize = 0;
		saveCollectionState();
		mPrefs.edit().putBoolean("cache_collection", mCacheCollection).commit();
	}
	
	public boolean isCacheCollection() {
		return mCacheCollection;
	}
}
