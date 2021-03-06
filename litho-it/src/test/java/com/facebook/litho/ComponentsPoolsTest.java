/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.litho;

import static com.facebook.litho.ComponentsPools.acquireMountContent;
import static com.facebook.litho.ComponentsPools.maybePreallocateContent;
import static com.facebook.litho.ComponentsPools.release;
import static org.assertj.core.api.Java6Assertions.assertThat;

import android.app.Activity;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import com.facebook.litho.testing.testrunner.ComponentsTestRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ActivityController;

@RunWith(ComponentsTestRunner.class)
public class ComponentsPoolsTest {

  private static final int POOL_SIZE = 2;
  private final ComponentLifecycle mLifecycle =
      new ComponentLifecycle() {
        @Override
        int getTypeId() {
          return 1;
        }

        @Override
        protected int poolSize() {
          return POOL_SIZE;
        }

        @Override
        public View onCreateMountContent(Object context) {
          return mNewMountContent;
        }
      };

  private final ComponentLifecycle mLifecycleWithEmptyPoolSize =
      new ComponentLifecycle() {
        @Override
        int getTypeId() {
          return 2;
        }

        @Override
        protected int poolSize() {
          return 0;
        }

        @Override
        public View onCreateMountContent(Object context) {
          return mNewMountContent;
        }
      };

  private ComponentContext mContext1;
  private ComponentContext mContext2;
  private ComponentContext mContext3;
  private ActivityController<Activity> mActivityController;
  private Activity mActivity;
  private ComponentContext mActivityComponentContext;
  private ColorDrawable mMountContent;
  private View mNewMountContent;

  @Before
  public void setup() {
    mContext1 = new ComponentContext(RuntimeEnvironment.application);
    mContext2 = new ComponentContext(new ComponentContext(RuntimeEnvironment.application));
    mContext3 = new ComponentContext(new ContextWrapper(RuntimeEnvironment.application));
    mActivityController = Robolectric.buildActivity(Activity.class).create();
    mActivity = mActivityController.get();
    mActivityComponentContext = new ComponentContext(mActivity);
    mMountContent = new ColorDrawable(Color.RED);
    mNewMountContent = new View(mContext1);
  }

  @After
  public void tearDown() {
    ComponentsPools.clearActivityCallbacks();
  }

  @Test
  public void testAcquireMountContentWithSameContext() {
    assertThat(acquireMountContent(mContext1, mLifecycle)).isSameAs(mNewMountContent);

    release(mContext1, mLifecycle, mMountContent);

    assertThat(mMountContent).isSameAs(acquireMountContent(mContext1, mLifecycle));
  }

  @Test
  public void testAcquireMountContentWithSameUnderlyingContext() {
    assertThat(acquireMountContent(mContext1, mLifecycle)).isSameAs(mNewMountContent);

    release(mContext1, mLifecycle, mMountContent);

    assertThat(mMountContent).isSameAs(acquireMountContent(mContext2, mLifecycle));
  }

  @Test
  public void testAcquireMountContentWithDifferentUnderlyingContext() {
    assertThat(acquireMountContent(mContext1, mLifecycle)).isSameAs(mNewMountContent);

    release(mContext1, mLifecycle, mMountContent);

    assertThat(acquireMountContent(mContext3, mLifecycle)).isSameAs(mNewMountContent);
  }

  @Test
  public void testReleaseMountContentForDestroyedContextDoesNothing() {
    // Assert pooling was working before
    assertThat(acquireMountContent(mActivityComponentContext, mLifecycle))
        .isSameAs(mNewMountContent);

    release(mActivityComponentContext, mLifecycle, mMountContent);

    assertThat(mMountContent).isSameAs(acquireMountContent(mActivityComponentContext, mLifecycle));

    // Now destroy it and assert pooling no longer works
    mActivityController.destroy();
    release(mActivityComponentContext, mLifecycle, mMountContent);

    assertThat(acquireMountContent(mActivityComponentContext, mLifecycle))
        .isSameAs(mNewMountContent);
  }

  @Test
  public void testDestroyingActivityDoesNotAffectPoolingOfOtherContexts() {
    mActivityController.destroy();
    ComponentsPools.onContextDestroyed(mActivity);

    release(mContext1, mLifecycle, mMountContent);

    assertThat(acquireMountContent(mContext1, mLifecycle)).isSameAs(mMountContent);
  }

  @Test
  public void testPreallocateContent() {
    assertThat(acquireMountContent(mContext1, mLifecycle)).isSameAs(mNewMountContent);

    maybePreallocateContent(mContext1, mLifecycle);

    // Change the content that's returned when we create new mount content to make sure we're
    // getting the one from preallocating above.
    mNewMountContent = new View(mContext1);
    assertThat(acquireMountContent(mContext1, mLifecycle)).isNotSameAs(mNewMountContent);
  }

  @Test
  public void testDoNotPreallocateContentBeyondPoolSize() {
    for (int i = 0; i < POOL_SIZE; i++) {
      maybePreallocateContent(mContext1, mLifecycle);
      acquireMountContent(mContext1, mLifecycle);
    }

    maybePreallocateContent(mContext1, mLifecycle);

    mNewMountContent = new View(mContext1);
    assertThat(acquireMountContent(mContext1, mLifecycle)).isSameAs(mNewMountContent);
  }

  @Test
  public void testAllocationsCountTowardsPreallocationLimit() {
    for (int i = 0; i < POOL_SIZE - 1; i++) {
      maybePreallocateContent(mContext1, mLifecycle);
      acquireMountContent(mContext1, mLifecycle);
    }
    acquireMountContent(mContext1, mLifecycle);

    // Allocation limit should be hit now, so we shouldn't preallocate anything
    maybePreallocateContent(mContext1, mLifecycle);

    mNewMountContent = new View(mContext1);
    assertThat(acquireMountContent(mContext1, mLifecycle)).isSameAs(mNewMountContent);
  }

  @Test
  public void testReleaseAndAcquireWithNoPoolSize() {
    release(mContext1, mLifecycleWithEmptyPoolSize, mMountContent);
    assertThat(acquireMountContent(mContext1, mLifecycleWithEmptyPoolSize))
        .isSameAs(mNewMountContent);
  }

  @Test
  public void testPreallocateWithEmptyPoolSize() {
    maybePreallocateContent(mContext1, mLifecycleWithEmptyPoolSize);

    mNewMountContent = new View(mContext1);
    assertThat(acquireMountContent(mContext1, mLifecycleWithEmptyPoolSize))
        .isSameAs(mNewMountContent);
  }
}
