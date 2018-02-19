import { Injectable } from '@angular/core';
import { Resolve, ActivatedRouteSnapshot, RouterStateSnapshot, Routes, CanActivate } from '@angular/router';

import { UserRouteAccessService } from '../../shared';
import { PaginationUtil } from 'ng-jhipster';

import { OperinoComponent } from './operino.component';
import { OperinoDetailComponent } from './operino-detail.component';
import { OperinoPopupComponent } from './operino-dialog.component';
import { OperinoDeletePopupComponent } from './operino-delete-dialog.component';

import { Principal } from '../../shared';


export const operinoRoute: Routes = [
  {
    path: 'operino',
    component: OperinoComponent,
    data: {
        authorities: ['ROLE_USER'],
        pageTitle: 'Operinos'
    }
  }, {
    path: 'operino/:id',
    component: OperinoDetailComponent,
    data: {
        authorities: ['ROLE_USER'],
        isEditing: false,
        pageTitle: 'Operinos'
    }
  },
    //{
    //    path: 'operino-new',
    //    component: OperinoPopupComponent,
    //    data: {
    //        authorities: ['ROLE_USER'],
    //        pageTitle: 'Operinos'
    //    }
    //},
    {
        path: 'operino/:id/edit',
        component: OperinoDetailComponent,
        data: {
            authorities: ['ROLE_USER'],
            isEditing: true,
            pageTitle: 'Operinos'
        }
    }
];

export const operinoPopupRoute: Routes = [
    {
        path: 'operino-new',
        component: OperinoPopupComponent,
        data: {
            authorities: ['ROLE_USER'],
            pageTitle: 'Operinos'
        },
        outlet: 'popup'
    },
  {
    path: 'operino/:id/delete',
    component: OperinoDeletePopupComponent,
    data: {
        authorities: ['ROLE_USER'],
        pageTitle: 'Operinos'
    },
    outlet: 'popup'
  }
];
